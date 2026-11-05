package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.model.ParamNames;
import ee.gaile.imagegen.model.workflow.VideoProfile;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static ee.gaile.imagegen.comfy.Graphs.link;
import static ee.gaile.imagegen.comfy.Graphs.node;

/**
 * The upscale and the frame interpolation of a finished video, the recipe of the author of the Wan weights:
 * 4x-NMKD-Siax scaled down to the long side wanted, then RIFE. The output node of the graph (SaveVideo) becomes
 * UpscaleInterpolateSaveVideo (comfyui-service/video_upscale): the same video input and the same output in the history,
 * but a chunk of frames at a time, so even 30 s stay within the RAM of cv-comfyui (the native nodes keep the whole
 * video at 4x: ~60 GB for 10 s at 960x544). A single clip gets it in its own graph, a long video in the join of its
 * scenes, after the sound. Time (RTX 5060 Ti, 2026-11-05): ~3.5 s a frame of 960x544, ~6 s of 1280x720 for the
 * upscale; the interpolation is cheap (~0.1 s a new frame at 1920x1088).
 */
@UtilityClass
class UpscaleGraphs {

    /** Ids of the loaders: above the sound nodes (400+) */
    static final String UPSCALER = "500";
    static final String INTERPOLATOR = "501";

    static final String NODE = "UpscaleInterpolateSaveVideo";
    static final String UPSCALE_MODEL = "4x_NMKD-Siax_200k.pth";
    static final String INTERPOLATION_MODEL = "rife_v4.26.safetensors";
    /** Frames upscaled and interpolated at a time: 16 at 1280x720 x4 are ~2.8 GB of float32 */
    static final int CHUNK_FRAMES = 16;
    /** The quality of SaveVideo (its default for h264) */
    static final int CRF = 18;

    /** The job asks for the upscale and the workflow gives it */
    static boolean upscales(Map<String, Object> params, VideoProfile video) {
        return Boolean.TRUE.equals(params.get(ParamNames.UPSCALE)) && video.upscaleTo() > 0;
    }

    /** The job asks for the smoothing and the workflow gives it */
    static boolean smooths(Map<String, Object> params, VideoProfile video) {
        return Boolean.TRUE.equals(params.get(ParamNames.SMOOTH)) && video.smoothBy() > 1;
    }

    /**
     * Makes the output node of the graph save the video upscaled to the long side of the profile and/or with
     * {@link VideoProfile#smoothBy()} times its frames.
     *
     * @param outputNode the SaveVideo node of the graph
     */
    static void apply(ObjectNode graph, String outputNode, VideoProfile video, boolean upscale, boolean smooth) {
        JsonNode save = graph.path(outputNode);
        if (!"SaveVideo".equals(save.path("class_type").asString(""))
                || !(save.get("inputs") instanceof ObjectNode old)) {
            throw new IllegalStateException("The output node " + outputNode + " is no SaveVideo");
        }
        ObjectNode inputs = node(graph, outputNode, NODE);
        inputs.set("video", old.get("video"));
        inputs.set("filename_prefix", old.get("filename_prefix"));
        // without the upscale the node keeps the size; the input is still required
        inputs.put("long_side", upscale ? video.upscaleTo() : Math.max(video.width(), video.height()));
        inputs.put("multiplier", smooth ? video.smoothBy() : 1);
        inputs.put("chunk_frames", CHUNK_FRAMES);
        inputs.put("crf", CRF);
        if (upscale) {
            node(graph, UPSCALER, "UpscaleModelLoader").put("model_name", UPSCALE_MODEL);
            inputs.set("upscale_model", link(UPSCALER, 0));
        }
        if (smooth) {
            node(graph, INTERPOLATOR, "FrameInterpolationModelLoader").put("model_name", INTERPOLATION_MODEL);
            inputs.set("interp_model", link(INTERPOLATOR, 0));
        }
    }

    /**
     * The size the node gives a video of this size: the long side of the profile, the proportions kept, even sides
     * (as target_size of the node)
     */
    static int[] size(int width, int height, VideoProfile video) {
        double scale = (double) video.upscaleTo() / Math.max(width, height);
        return new int[]{Math.max(2, (int) Math.round(width * scale / 2) * 2),
                Math.max(2, (int) Math.round(height * scale / 2) * 2)};
    }
}
