package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.model.workflow.FaceAnchor;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static ee.gaile.imagegen.comfy.Graphs.link;
import static ee.gaile.imagegen.comfy.Graphs.node;

/**
 * Rewires a filled video workflow so that it keeps the face of the start image, see {@link FaceAnchor}. The node ids
 * are those of the graphs that name the anchor in their params file: wan22-i2v-fastmove has one layout,
 * ltx25-nsfw-i2v another.
 */
@UtilityClass
class FaceAnchors {

    /** LTX: one guide frame about every this many seconds, the last one on the last frame */
    static final double LTX_GUIDE_SECONDS = 2.7;
    /**
     * Weight of each guide frame: at 1.0 the head jumps back to the photo at every guide (a turn of the head breaks
     * into twitches), at 0.3 the face and the room drift away; 0.5 keeps both the face and the motion (test 2026-10-27)
     */
    static final double LTX_GUIDE_STRENGTH = 0.5;
    /** LTX compresses 8 frames into one latent frame: guides go on its boundaries */
    private static final int LTX_FRAME_STEP = 8;
    /** Ids of the added nodes start here, far above those of the templates */
    private static final int FIRST_NEW_NODE = 100;

    /**
     * @param frames the frames of the clip
     * @param fps    its frame rate
     */
    static void apply(ObjectNode graph, FaceAnchor anchor, int frames, int fps) {
        switch (anchor) {
            case WAN_LAST_FRAME -> wanLastFrame(graph);
            case LTX_GUIDES -> ltxGuides(graph, frames, fps);
        }
    }

    /** Node 12 (WanImageToVideo) becomes WanFirstLastFrameToVideo with the start image as the end image too */
    private static void wanLastFrame(ObjectNode graph) {
        ObjectNode inputs = input(graph, "12");
        ((ObjectNode) graph.get("12")).put("class_type", "WanFirstLastFrameToVideo");
        inputs.set("end_image", inputs.get("start_image").deepCopy());
    }

    /**
     * The guide frames go between the first-frame conditioning and the sampler; the sampler gets the guided
     * conditioning, and the guides are cropped off the latent after it. One stage at the final size (the graph of
     * the author of the LTX weights).
     */
    private static void ltxGuides(ObjectNode graph, int frames, int fps) {
        List<Integer> guides = ltxGuideFrames(frames, fps);
        int[] next = {FIRST_NEW_NODE};
        // the latent of node 12 (the start image in place), sampled by node 15, split by node 16, decoded by node 17
        ArrayNode[] guided = addGuides(graph, guides, next, link("10", 0), link("10", 1), link("12", 0));
        input(graph, "14").set("video_latent", guided[2]);
        input(graph, "15").set("positive", guided[0]);
        input(graph, "15").set("negative", guided[1]);
        String crop = cropGuides(graph, next, guided, link("16", 0));
        input(graph, "17").set("samples", link(crop, 2));
    }

    /**
     * @return frame indexes of the guides: evenly spread, on latent frame boundaries, the last one is -1 (the end)
     */
    static List<Integer> ltxGuideFrames(int frames, int fps) {
        double seconds = (frames - 1) / (double) fps;
        int count = Math.max(1, (int) Math.round(seconds / LTX_GUIDE_SECONDS));
        List<Integer> guides = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            guides.add((frames - 1) * i / count / LTX_FRAME_STEP * LTX_FRAME_STEP);
        }
        guides.add(-1);
        return guides;
    }

    /** @return positive, negative and latent after the chain of LTXVAddGuide nodes */
    private static ArrayNode[] addGuides(ObjectNode graph, List<Integer> guides, int[] next, ArrayNode positive,
                                         ArrayNode negative, ArrayNode latent) {
        ArrayNode[] links = {positive, negative, latent};
        for (int frame : guides) {
            String id = id(next);
            ObjectNode inputs = node(graph, id, "LTXVAddGuide");
            inputs.set("positive", links[0]);
            inputs.set("negative", links[1]);
            inputs.set("vae", link("3", 0));
            inputs.set("latent", links[2]);
            inputs.set("image", link("6", 0));
            inputs.put("frame_idx", frame);
            inputs.put("strength", LTX_GUIDE_STRENGTH);
            links = new ArrayNode[]{link(id, 0), link(id, 1), link(id, 2)};
        }
        return links;
    }

    private static String cropGuides(ObjectNode graph, int[] next, ArrayNode[] guided, ArrayNode sampled) {
        String id = id(next);
        ObjectNode inputs = node(graph, id, "LTXVCropGuides");
        inputs.set("positive", guided[0]);
        inputs.set("negative", guided[1]);
        inputs.set("latent", sampled);
        return id;
    }

    private static ObjectNode input(ObjectNode graph, String node) {
        JsonNode inputs = graph.path(node).get("inputs");
        if (!(inputs instanceof ObjectNode object)) {
            throw new IllegalStateException("The workflow has no node " + node + " to anchor the face");
        }
        return object;
    }

    private static String id(int[] next) {
        return String.valueOf(next[0]++);
    }
}
