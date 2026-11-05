"""The upscale and the frame interpolation of a finished video, written straight into its mp4 (imagegen task 6).

The recipe of the author of the Wan weights: 4x-NMKD-Siax, scaled down to the size wanted, then RIFE. The native
nodes of ComfyUI do it on the whole video at once: ImageUpscaleWithModel keeps all frames at 4x in float32 and
FrameInterpolate all the new frames, ~60 GB of RAM for a 10 s video at 960x544 (2026-11-05), so 30 s does not fit the
72 GB of cv-comfyui. This node takes the same steps a chunk of frames at a time - the same upscale and interpolation
code of ComfyUI, called per chunk - and encodes each chunk as it is done, so it holds one chunk only. The last frame of
a chunk is the first of the next interpolation, so the result is the same as in one piece. The sound of the video is
encoded as SaveVideo does.

It takes the place of SaveVideo (same VIDEO input, same "images" + "animated" output in the history), so a graph gets
it by swapping the class of its output node.
"""
import math
import os
from fractions import Fraction

import av
import torch

import comfy.utils
import folder_paths
from comfy import model_management
from comfy_api.latest._input_impl.video_types import (BT709_NCL, VIDEO_ENCODERS, set_video_color_properties,
                                                      video_encoder_options)
from comfy_api.latest._util import VideoCodec
from comfy_extras.nodes_frame_interpolation import FrameInterpolate
from comfy_extras.nodes_upscale_model import ImageUpscaleWithModel

COLOR_SPACE = "sRGB"


def quietly(call):
    """Runs a native node without its own progress bar: its bar restarts with every chunk, the bar of this node counts
    the frames of the whole video (cv-imagegen-app shows it as the upscale)"""
    hook = comfy.utils.PROGRESS_BAR_HOOK
    comfy.utils.set_progress_bar_global_hook(None)
    try:
        return call()
    finally:
        comfy.utils.set_progress_bar_global_hook(hook)


def target_size(width, height, long_side):
    """The size with the long side given and the proportions kept, even numbers (yuv420p); halves round up, as
    Math.round of the service (UpscaleGraphs.size), which reports this size"""
    scale = long_side / max(width, height)
    return (max(2, math.floor(width * scale / 2 + 0.5) * 2),
            max(2, math.floor(height * scale / 2 + 0.5) * 2))


class UpscaleInterpolateSaveVideo:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "video": ("VIDEO",),
                "long_side": ("INT", {"default": 1920, "min": 64, "max": 8192, "step": 2,
                                      "tooltip": "the long side of the upscaled video; the proportions stay"}),
                "multiplier": ("INT", {"default": 1, "min": 1, "max": 8,
                                       "tooltip": "1 = no interpolation; n = n times the frame rate"}),
                "chunk_frames": ("INT", {"default": 16, "min": 1, "max": 1024}),
                "crf": ("INT", {"default": 18, "min": 0, "max": 51}),
                "filename_prefix": ("STRING", {"default": "video/upscaled"}),
            },
            "optional": {
                "upscale_model": ("UPSCALE_MODEL", {"tooltip": "none: the frames keep their size"}),
                "interp_model": ("INTERP_MODEL", {"tooltip": "none: no new frames"}),
            },
        }

    RETURN_TYPES = ()
    OUTPUT_NODE = True
    FUNCTION = "save"
    CATEGORY = "video"

    def save(self, video, long_side, multiplier, chunk_frames, crf, filename_prefix, upscale_model=None,
             interp_model=None):
        components = video.get_components()
        images = components.images
        count, height, width = images.shape[0], images.shape[1], images.shape[2]
        fps = float(components.frame_rate)
        multiplier = multiplier if interp_model is not None and count > 1 else 1
        out_width, out_height = target_size(width, height, long_side) if upscale_model is not None             else (width, height)

        folder, filename, counter, subfolder, _ = folder_paths.get_save_image_path(
            filename_prefix, folder_paths.get_output_directory(), out_width, out_height)
        file = f"{filename}_{counter:05}_.mp4"
        path = os.path.join(folder, file)

        pbar = comfy.utils.ProgressBar(count)
        with av.open(path, mode="w", format="mp4") as output:
            stream = output.add_stream(VIDEO_ENCODERS[VideoCodec.H264],
                                       rate=Fraction(round(fps * multiplier * 1000), 1000))
            stream.width = out_width
            stream.height = out_height
            stream.pix_fmt = "yuv420p"
            stream.options = video_encoder_options(VideoCodec.H264, crf)
            set_video_color_properties(stream.codec_context, COLOR_SPACE)
            # the streams before the first packet: the header of the mp4 is written with it
            audio, audio_stream = components.audio, None
            if audio:
                rate = int(audio["sample_rate"])
                waveform = audio["waveform"][0, :, :math.ceil(rate / fps * count)]
                layout = {1: "mono", 2: "stereo", 6: "5.1"}.get(waveform.shape[0], "stereo")
                audio_stream = output.add_stream("aac", rate=rate, layout=layout)

            previous = None
            for start in range(0, count, chunk_frames):
                model_management.throw_exception_if_processing_interrupted()
                chunk = images[start:start + chunk_frames]
                scaled = chunk
                if upscale_model is not None:
                    upscaled = quietly(lambda: ImageUpscaleWithModel.execute(upscale_model, chunk).result[0])
                    scaled = comfy.utils.common_upscale(upscaled.movedim(-1, 1), out_width, out_height, "lanczos",
                                                        "disabled").movedim(1, -1)
                    del upscaled
                frames = scaled
                if multiplier > 1:
                    sequence = scaled if previous is None else torch.cat((previous, scaled))
                    frames = quietly(lambda: FrameInterpolate.execute(interp_model, sequence, multiplier).result[0])
                    if previous is not None:
                        # the first frame is the last one of the chunk before, already written
                        frames = frames[1:]
                    previous = scaled[-1:].clone()
                for frame in frames:
                    picture = av.VideoFrame.from_ndarray((frame * 255).clamp(0, 255).byte().cpu().numpy(),
                                                         format="rgb24")
                    picture = picture.reformat(format="yuv420p", dst_colorspace=BT709_NCL)
                    set_video_color_properties(picture, COLOR_SPACE)
                    output.mux(stream.encode(picture))
                del frames, scaled
                pbar.update(chunk.shape[0])
            output.mux(stream.encode(None))

            if audio_stream is not None:
                frame = av.AudioFrame.from_ndarray(waveform.float().cpu().contiguous().numpy(), format="fltp",
                                                   layout=layout)
                frame.sample_rate = rate
                frame.pts = 0
                output.mux(audio_stream.encode(frame))
                output.mux(audio_stream.encode(None))

        return {"ui": {"images": [{"filename": file, "subfolder": subfolder, "type": "output"}],
                       "animated": (True,)}}


NODE_CLASS_MAPPINGS = {"UpscaleInterpolateSaveVideo": UpscaleInterpolateSaveVideo}
NODE_DISPLAY_NAME_MAPPINGS = {"UpscaleInterpolateSaveVideo": "Upscale + Interpolate + Save Video (chunked)"}
