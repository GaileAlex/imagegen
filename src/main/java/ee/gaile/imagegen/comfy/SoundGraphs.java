package ee.gaile.imagegen.comfy;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.regex.Pattern;

import static ee.gaile.imagegen.comfy.Graphs.link;
import static ee.gaile.imagegen.comfy.Graphs.node;

/**
 * The sound of a soundless video (Wan): MMAudio (ComfyUI-MMAudio of kijai, patched by comfyui-service/
 * patch_mmaudio.py) makes it from the frames and a short text, and the node that builds the video takes it as its
 * audio. A single clip gets it in its own graph, a long video in the join of its scenes (one sound for all of it, no
 * break at the seams). The base model: the NSFW fine-tune put women's moans into every clip (test 2026-11-02).
 * ~6.5 GB of VRAM; the patched sampler unloads the video model before it. Wan 5 s: +~25 s, 20 s film: +~25 s.
 */
@UtilityClass
class SoundGraphs {

    /** Ids of the sound nodes: above the templates (up to ~30), the face anchors (100+) and the tail (190+) */
    static final String MODEL = "400";
    static final String FEATURES = "401";
    static final String SAMPLER = "402";
    /** The frames and the frame rate of the joined scenes, the video with the sound */
    static final String JOINED_PARTS = "310";
    static final String JOINED_WITH_SOUND = "311";

    static final String MODEL_FILE = "mmaudio_large_44k_v2_fp16.safetensors";
    /** The text encoder of MMAudio is CLIP: ~77 tokens, the rest is cut off */
    static final int MAX_PROMPT = 300;
    static final String NEGATIVE = "music, speech, talking, singing, moaning";
    /** Longer than any video: the patched sampler takes the length of the frames */
    private static final double ANY_LENGTH = 600.0;
    private static final Pattern SCENE_MARK = Pattern.compile("(?m)^\\s*\\[[^\\]\\n]*\\]\\s*$");

    /**
     * Adds the sound to the graph of a clip: the decoded frames go to MMAudio, its sound to the video node.
     *
     * @param decoded     the node whose output 0 is the decoded frames
     * @param outputNode  the SaveVideo node of the template, its "video" input names the node that builds the video
     */
    static void addToClip(ObjectNode graph, String decoded, String outputNode, String prompt, long seed) {
        JsonNode video = graph.path(outputNode).path("inputs").path("video");
        String create = video.path(0).asString("");
        if (!(graph.path(create).path("inputs") instanceof ObjectNode createInputs)
                || !"CreateVideo".equals(graph.path(create).path("class_type").asString(""))) {
            throw new IllegalStateException("No CreateVideo node feeds " + outputNode);
        }
        addSampler(graph, link(decoded, 0), createInputs.get("fps").deepCopy(), prompt, seed);
        createInputs.set("audio", link(SAMPLER, 0));
    }

    /**
     * Adds the sound to the join of the scenes of a long video: the joined video is split into its frames, MMAudio
     * makes the sound of all of it and the video is built again with it.
     *
     * @param join the graph of {@link SceneGraphs#join}
     */
    static void addToJoin(ObjectNode join, String prompt, long seed) {
        ObjectNode parts = node(join, JOINED_PARTS, "GetVideoComponents");
        parts.set("video", link(SceneGraphs.JOIN, 0));
        addSampler(join, link(JOINED_PARTS, 0), link(JOINED_PARTS, 2), prompt, seed);
        ObjectNode create = node(join, JOINED_WITH_SOUND, "CreateVideo");
        create.set("images", link(JOINED_PARTS, 0));
        create.set("fps", link(JOINED_PARTS, 2));
        create.set("audio", link(SAMPLER, 0));
        ((ObjectNode) join.get(SceneGraphs.JOIN_SAVE).get("inputs")).set("video", link(JOINED_WITH_SOUND, 0));
    }

    /**
     * The text MMAudio gets: the video prompt without the scene marks, cut to what its text encoder reads
     */
    static String prompt(String videoPrompt) {
        String text = SCENE_MARK.matcher(videoPrompt == null ? "" : videoPrompt).replaceAll(" ")
                .replaceAll("\\s+", " ").strip();
        if (text.length() <= MAX_PROMPT) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_PROMPT);
        return text.substring(0, cut > 0 ? cut : MAX_PROMPT);
    }

    private static void addSampler(ObjectNode graph, ArrayNode images, JsonNode fps, String prompt, long seed) {
        ObjectNode model = node(graph, MODEL, "MMAudioModelLoader");
        model.put("mmaudio_model", MODEL_FILE);
        model.put("base_precision", "fp16");
        ObjectNode features = node(graph, FEATURES, "MMAudioFeatureUtilsLoader");
        features.put("vae_model", "mmaudio_vae_44k_fp16.safetensors");
        features.put("synchformer_model", "mmaudio_synchformer_fp16.safetensors");
        features.put("clip_model", "apple_DFN5B-CLIP-ViT-H-14-384_fp16.safetensors");
        features.put("mode", "44k");
        features.put("precision", "fp16");
        ObjectNode sampler = node(graph, SAMPLER, "MMAudioSampler");
        sampler.set("mmaudio_model", link(MODEL, 0));
        sampler.set("feature_utils", link(FEATURES, 0));
        sampler.put("duration", ANY_LENGTH);
        sampler.put("steps", 25);
        sampler.put("cfg", 4.5);
        // the seed input of the node takes 0..2^64-1
        sampler.put("seed", seed & Long.MAX_VALUE);
        sampler.put("prompt", prompt);
        sampler.put("negative_prompt", NEGATIVE);
        sampler.put("mask_away_clip", false);
        sampler.put("force_offload", true);
        sampler.set("images", images);
        sampler.set("fps", fps);
    }
}
