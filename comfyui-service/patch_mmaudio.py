# Fixes of MMAudio's sampler node for the videos of gaile.ee. Fails the build when the upstream code changes, so the
# patch is never applied blindly.
# 1. The node takes the first 8 x seconds frames of the video as its 8 fps CLIP frames and the first 25 x seconds as
#    its 25 fps sync frames: right only for a video of exactly that rate. A Wan video has 16 fps, so the CLIP frames
#    would cover half of it and the sound would be cut to frames / 25 seconds. An "fps" input (the real rate of the
#    frames) picks the frames by time instead; fps 0 keeps the old behaviour.
# 2. Sound shorter than 6 s crawls on the RTX 5060 Ti (3-15 s a step instead of 0.12 s, the CUDA allocator fails
#    every step, the whole PC stutters; sweep of 2026-11-02: 2-5.5 s slow, 6-20 s fine). With fps given, a shorter
#    video is lengthened to 8 s (the training length of MMAudio) by its last frame and the sound cut back to it.
# 3. The sampler moves its models to the GPU outside the memory management of ComfyUI: the models of ComfyUI (the
#    video model of the same prompt) leave the VRAM first.
import pathlib
import sys

nodes = pathlib.Path(sys.argv[1]) / "nodes.py"
text = nodes.read_text(encoding="utf-8")


def replace_once(old, new):
    global text
    if text.count(old) != 1:
        sys.exit("ComfyUI-MMAudio nodes.py changed, not found once: " + old[:80])
    text = text.replace(old, new)


replace_once(
    "def process_video_tensor(video_tensor: torch.Tensor, duration_sec: float) -> tuple[torch.Tensor, torch.Tensor, float]:\n",
    "SHORTEST_SOUND = 8.0\n"
    "\n"
    "\n"
    "def resample_frames(video_tensor: torch.Tensor, fps: float, rate: float, duration_sec: float) -> torch.Tensor:\n"
    "    \"\"\"The frames at the given rate, picked by time from frames of the real rate fps; past the end the last frame\n"
    "    (gaile.ee patch)\"\"\"\n"
    "    count = int(rate * duration_sec)\n"
    "    indices = (torch.arange(count, dtype=torch.float64) * fps / rate).floor().long()\n"
    "    return video_tensor[indices.clamp(max=video_tensor.shape[0] - 1)]\n"
    "\n"
    "\n"
    "def process_video_tensor(video_tensor: torch.Tensor, duration_sec: float, fps: float = 0.0) -> tuple[torch.Tensor, torch.Tensor, float]:\n")
replace_once(
    "    # Assuming video_tensor is in the shape (frames, height, width, channels)\n",
    "    # Assuming video_tensor is in the shape (frames, height, width, channels)\n"
    "    if fps > 0:\n"
    "        duration_sec = max(SHORTEST_SOUND, duration_sec)\n"
    "        clip_frames = resample_frames(video_tensor, fps, _CLIP_FPS, duration_sec).permute(0, 3, 1, 2)\n"
    "        sync_frames = resample_frames(video_tensor, fps, _SYNC_FPS, duration_sec).permute(0, 3, 1, 2)\n"
    "        clip_frames = torch.stack([clip_transform(frame) for frame in clip_frames])\n"
    "        sync_frames = torch.stack([sync_transform(frame) for frame in sync_frames])\n"
    "        return clip_frames, sync_frames, duration_sec\n")
replace_once(
    "            \"optional\": {\n                \"images\": (\"IMAGE\",),\n            },\n",
    "            \"optional\": {\n                \"images\": (\"IMAGE\",),\n"
    "                \"fps\": (\"FLOAT\", {\"default\": 0.0, \"min\": 0.0, \"tooltip\": \"Frame rate of the images (0 = 8/25 fps as given)\"}),\n"
    "            },\n")
replace_once(
    "mask_away_clip, force_offload, images=None):",
    "mask_away_clip, force_offload, images=None, fps=0.0):")
replace_once(
    "            clip_frames, sync_frames, duration = process_video_tensor(images, duration)\n",
    "            if fps > 0:\n"
    "                duration = min(duration, images.shape[0] / fps)\n"
    "            real_duration = duration\n"
    "            clip_frames, sync_frames, duration = process_video_tensor(images, duration, fps)\n")
replace_once(
    "        feature_utils.to(device)\n        mmaudio_model.to(device)\n",
    "        mm.unload_all_models()\n        mm.soft_empty_cache()\n"
    "        feature_utils.to(device)\n        mmaudio_model.to(device)\n")
replace_once(
    "        waveform = audios.float().cpu()\n",
    "        waveform = audios.float().cpu()\n"
    "        if images is not None and fps > 0:\n"
    "            waveform = waveform[..., :int(round(real_duration * 44100))]\n")
nodes.write_text(text, encoding="utf-8")
print("MMAudio: frames by the real frame rate, short sound made 8 s and cut, ComfyUI models unloaded first")
