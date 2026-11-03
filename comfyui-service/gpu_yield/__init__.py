"""Speech before pictures: the GPU of ComfyUI is shared with the speech services of the chat (Whisper, the TTS).

While cv-imagegen-app holds the GPU for them (POST /gpu_yield/hold), the prompt that runs waits before its next layer:
comfy.ops.run_every_op is called by every layer of ComfyUI (it checks the cancel there), so the wait starts within
milliseconds, the GPU computes nothing of ComfyUI meanwhile and the prompt goes on from the same place when the hold
ends. Nothing is unloaded by the wait itself.

A hold may ask for room (room_mb): Whisper loads a model only after it has been unloaded for idleness, and a video
model fills the VRAM. The waiting prompt then gives VRAM back: pages of its models that DynamicVRAM (comfy-aimdo) holds
are evicted, and the headroom of aimdo is raised for the hold, so a fault does not take the room again. When the hold
ends the headroom goes back and the pages come back on their next use as far as the VRAM allows.

The cancel (POST /interrupt) ends a wait at once. A hold ends by itself and is capped (MAX_HOLD): the callers renew it
while the speech goes on, so a caller that is gone never stops ComfyUI for long.
"""
import collections
import ctypes
import logging
import os
import threading
import time

import torch
from aiohttp import web

import comfy.model_management
import comfy.ops
from server import PromptServer

try:
    import comfy_aimdo.control as aimdo_control
except ImportError:
    aimdo_control = None

NODE_CLASS_MAPPINGS = {}

log = logging.getLogger("gpu_yield")

MB = 1024 * 1024
# a hold longer than this is cut: the callers renew it while the speech goes on
MAX_HOLD = 120.0
# the waiting prompt looks at the hold and the cancel this often
POLL = 0.25

_cond = threading.Condition()
# time.monotonic() until which the GPU is held; 0 = not held
_hold_until = 0.0
# the VRAM the hold wants free, bytes; 0 = no room asked
_room = 0
# the room of the hold was made (or tried) by the waiting prompt
_room_done = True
# a prompt waits now
_waiting = False
# the headroom of aimdo when nothing is held
_base_headroom = None


def _now():
    return time.monotonic()


class _NvmlMemory(ctypes.Structure):
    _fields_ = [("total", ctypes.c_ulonglong), ("free", ctypes.c_ulonglong), ("used", ctypes.c_ulonglong)]


def _nvml_device():
    try:
        nvml = ctypes.CDLL("libnvidia-ml.so.1")
        handle = ctypes.c_void_p()
        if nvml.nvmlInit_v2() == 0 and nvml.nvmlDeviceGetHandleByIndex_v2(0, ctypes.byref(handle)) == 0:
            return nvml, handle
    except OSError:
        pass
    log.warning("gpu_yield: no NVML, the free VRAM is what CUDA reports")
    return None, None


_nvml, _nvml_handle = _nvml_device()


def _free_vram():
    """The VRAM free on the card. Under WSL CUDA reports only the memory of this process as used (15.1 GB free with
    6.9 GB taken by Whisper and the TTS, 2026-11-02); NVML counts every process, as nvidia-smi does."""
    if _nvml is not None:
        memory = _NvmlMemory()
        if _nvml.nvmlDeviceGetMemoryInfo(_nvml_handle, ctypes.byref(memory)) == 0:
            return memory.free
    free, _ = torch.cuda.mem_get_info()
    return free


def _aimdo_ready():
    if aimdo_control is None:
        return False
    try:
        aimdo_control.get_simple_vram_headroom()
        return True
    except RuntimeError:
        # not initialized: ComfyUI runs without DynamicVRAM
        return False


def _set_headroom(room):
    """Under _cond. The headroom of aimdo for the room of a hold, or back to what it was."""
    global _base_headroom
    if not _aimdo_ready():
        return
    if _base_headroom is None:
        _base_headroom = aimdo_control.get_simple_vram_headroom()
    aimdo_control.set_simple_vram_headroom(max(_base_headroom, room))


def _expire():
    """Under _cond: a hold that has ended gives the room back."""
    global _hold_until, _room, _room_done
    if _hold_until and _hold_until <= _now():
        _hold_until = 0.0
        if _room:
            _room = 0
            _room_done = True
            _set_headroom(0)
        log.info("GPU hold ended")


def _watch():
    """Ends the holds on time even when no prompt waits for them."""
    while True:
        with _cond:
            if _hold_until:
                _cond.wait(max(_hold_until - _now(), 0.01))
                _expire()
            else:
                _cond.wait()


def _give_back(room):
    """In the waiting prompt: evicts pages of the loaded models until the room is free.

    :return: the bytes free afterwards
    """
    # the kernels already queued still read the pages of the models
    torch.cuda.synchronize()
    missing = room - _free_vram()
    given = 0
    if missing > 0:
        for loaded in list(comfy.model_management.current_loaded_models):
            model = loaded.model
            if model is None or not model.is_dynamic():
                continue
            vbar = model._vbar_get()
            if vbar is None:
                continue
            given += vbar.free_memory(missing - given)
            if given >= missing:
                break
        # the cached blocks of torch that the eviction left free
        comfy.model_management.soft_empty_cache()
    free = _free_vram()
    log.info("Room for the speech: %d MB given back, %d MB free (%d MB asked)", given // MB, free // MB, room // MB)
    return free


def _wait():
    """Waits in the prompt while the GPU is held."""
    global _waiting, _room_done
    started = _now()
    with _cond:
        _waiting = True
    try:
        while True:
            with _cond:
                _expire()
                if not _hold_until:
                    break
                room = _room if not _room_done else 0
                _room_done = True
            if comfy.model_management.processing_interrupted():
                # the cancel: the original run_every_op raises it right after
                break
            if room:
                try:
                    _give_back(room)
                except Exception:
                    log.exception("Room for the speech failed")
            with _cond:
                if _hold_until and _room_done:
                    _cond.wait(min(max(_hold_until - _now(), 0.0), POLL))
    finally:
        with _cond:
            _waiting = False
    log.info("Prompt waited %.1f s for the speech", _now() - started)


class _Ahead:
    """Keeps the GPU work queued by ComfyUI short.

    The layers are launched asynchronously, so the CPU runs ahead of the GPU: up to 7 s of queued work was seen in a
    Wan prompt, and a fault of DynamicVRAM synchronizes the device and waits for all of it. A hold that comes then is
    seen only after those seconds (pause latency 0.1-7.4 s, 2026-11-02). An event per layer marks the queue; once more
    than `seconds` of GPU work (by the measured time of the layers) is in it, the CPU waits for the oldest layer.
    The GPU still has work queued, so it never idles for that.
    """

    MAX_LAYERS = 1024

    def __init__(self):
        # GPU_YIELD_AHEAD_SECONDS=0 switches the limit off; POST /gpu_yield/ahead changes it at run time
        self.seconds = float(os.getenv("GPU_YIELD_AHEAD_SECONDS", "0.2"))
        self.min_layers = int(os.getenv("GPU_YIELD_AHEAD_MIN_LAYERS", "4"))
        self.limit = self.MAX_LAYERS
        self.events = collections.deque()
        # GPU seconds of a layer, a moving average of the finished ones
        self.layer_seconds = 0.001

    def mark(self):
        if self.seconds <= 0 or not torch.cuda.is_available():
            return
        event = torch.cuda.Event(enable_timing=True)
        event.record()
        self.events.append(event)
        while len(self.events) > 1 and self.events[0].query() and self.events[1].query():
            self._learn()
        self.limit = min(max(int(self.seconds / self.layer_seconds), self.min_layers), self.MAX_LAYERS)
        while len(self.events) > self.limit:
            self.events[1].synchronize()
            self._learn()

    def _learn(self):
        first = self.events.popleft()
        seconds = first.elapsed_time(self.events[0]) / 1000.0
        self.layer_seconds = 0.9 * self.layer_seconds + 0.1 * max(seconds, 1e-5)

    def reset(self):
        """After a wait: the queue is empty, the old events say nothing of the next layers."""
        self.events.clear()


_ahead = _Ahead()
_original_run_every_op = comfy.ops.run_every_op


def run_every_op():
    if torch.compiler.is_compiling():
        _original_run_every_op()
        return
    # a float compare per layer while nothing is held
    if _hold_until:
        _wait()
        _ahead.reset()
    _original_run_every_op()
    try:
        _ahead.mark()
    except RuntimeError:
        # an event on a device without a context (a CPU model): no queue to keep short
        _ahead.reset()


comfy.ops.run_every_op = run_every_op


def _state():
    with _cond:
        remaining = max(_hold_until - _now(), 0.0) if _hold_until else 0.0
        return {"heldSeconds": round(remaining, 2), "roomMb": _room // MB, "waiting": _waiting,
                "freeMb": _free_vram() // MB, "aheadSeconds": _ahead.seconds,
                "layerMs": round(_ahead.layer_seconds * 1000, 3), "aheadLayers": _ahead.limit}


@PromptServer.instance.routes.post("/gpu_yield/hold")
async def hold(request):
    """Body: {"seconds": float, "room_mb": int (optional)}. A hold only grows: the later end wins, the larger room."""
    global _hold_until, _room, _room_done
    body = await request.json()
    seconds = min(max(float(body.get("seconds", 0)), 0.0), MAX_HOLD)
    room = max(int(body.get("room_mb", 0)), 0) * MB
    with _cond:
        started = not _hold_until
        _hold_until = max(_hold_until, _now() + seconds)
        if room:
            # every ask is checked against the VRAM free now: a second model may need room after the first
            _room = max(_room, room)
            _room_done = False
            _set_headroom(_room)
        _cond.notify_all()
    if started:
        log.info("GPU held for %.0f s%s", seconds, f", room {room // MB} MB" if room else "")
    return web.json_response(_state())


@PromptServer.instance.routes.post("/gpu_yield/ahead")
async def ahead(request):
    """Body: {"seconds": float, "min_layers": int}: the limit of the queued GPU work, see _Ahead."""
    body = await request.json()
    _ahead.seconds = max(float(body.get("seconds", _ahead.seconds)), 0.0)
    _ahead.min_layers = max(int(body.get("min_layers", _ahead.min_layers)), 1)
    _ahead.reset()
    log.info("Queued GPU work limited to %.2f s, at least %d layers", _ahead.seconds, _ahead.min_layers)
    return web.json_response(_state())


@PromptServer.instance.routes.get("/gpu_yield/state")
async def state(request):
    return web.json_response(_state())


threading.Thread(target=_watch, name="gpu_yield", daemon=True).start()
log.info("gpu_yield: the prompts wait while the speech holds the GPU")
