package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.model.workflow.FaceAnchor;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewires a filled video workflow so that it keeps the face of the start image, see {@link FaceAnchor}. The node ids
 * are those of the graphs that name the anchor in their params file: wan22-i2v and wan22-i2v-fastmove share one
 * layout, ltx25-i2v and ltx25-nsfw-i2v another.
 */
@UtilityClass
public class FaceAnchors {

    /** LTX: one guide frame about every this many seconds, the last one on the last frame */
    static final double LTX_GUIDE_SECONDS = 2.7;
    private static final int LTX_FPS = 24;
    /** LTX compresses 8 frames into one latent frame: guides go on its boundaries */
    private static final int LTX_FRAME_STEP = 8;
    /** Ids of the added nodes start here, far above those of the templates */
    private static final int FIRST_NEW_NODE = 100;

    public static void apply(ObjectNode graph, FaceAnchor anchor, int frames) {
        switch (anchor) {
            case WAN_LAST_FRAME -> wanLastFrame(graph);
            case LTX_GUIDES -> ltxGuides(graph, frames);
        }
    }

    /** Node 12 (WanImageToVideo) becomes WanFirstLastFrameToVideo with the start image as the end image too */
    private static void wanLastFrame(ObjectNode graph) {
        ObjectNode inputs = input(graph, "12");
        ((ObjectNode) graph.get("12")).put("class_type", "WanFirstLastFrameToVideo");
        inputs.set("end_image", inputs.get("start_image").deepCopy());
    }

    /**
     * The guide frames go between the first-frame conditioning and the sampler on both stages; each stage gets its
     * own guider with the guided conditioning, and the guides are cropped off the latent after the sampler.
     */
    private static void ltxGuides(ObjectNode graph, int frames) {
        List<Integer> guides = ltxGuideFrames(frames);
        int[] next = {FIRST_NEW_NODE};

        // stage 1: half size latent of node 12, sampled by node 19 into node 20, upscaled by node 21
        ArrayNode[] stage1 = addGuides(graph, guides, next, link("10", 0), link("10", 1), link("12", 0));
        input(graph, "14").set("video_latent", stage1[2]);
        input(graph, "15").set("positive", stage1[0]);
        input(graph, "15").set("negative", stage1[1]);
        String crop1 = cropGuides(graph, next, stage1, link("20", 0));
        input(graph, "21").set("samples", link(crop1, 2));

        // stage 2: full size latent of node 22, sampled by node 26 with guider 15 so far, decoded by node 28
        ArrayNode[] stage2 = addGuides(graph, guides, next, link(crop1, 0), link(crop1, 1), link("22", 0));
        input(graph, "23").set("video_latent", stage2[2]);
        String guider = id(next);
        ObjectNode guiderNode = (ObjectNode) graph.get("15").deepCopy();
        ((ObjectNode) guiderNode.get("inputs")).set("positive", stage2[0]);
        ((ObjectNode) guiderNode.get("inputs")).set("negative", stage2[1]);
        graph.set(guider, guiderNode);
        input(graph, "26").set("guider", link(guider, 0));
        String crop2 = cropGuides(graph, next, stage2, link("27", 0));
        input(graph, "28").set("samples", link(crop2, 2));
    }

    /**
     * @return frame indexes of the guides: evenly spread, on latent frame boundaries, the last one is -1 (the end)
     */
    static List<Integer> ltxGuideFrames(int frames) {
        double seconds = (frames - 1) / (double) LTX_FPS;
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
            ObjectNode inputs = graph.putObject(id).put("class_type", "LTXVAddGuide").putObject("inputs");
            inputs.set("positive", links[0]);
            inputs.set("negative", links[1]);
            inputs.set("vae", link("3", 0));
            inputs.set("latent", links[2]);
            inputs.set("image", link("7", 0));
            inputs.put("frame_idx", frame);
            inputs.put("strength", 1.0);
            links = new ArrayNode[]{link(id, 0), link(id, 1), link(id, 2)};
        }
        return links;
    }

    private static String cropGuides(ObjectNode graph, int[] next, ArrayNode[] guided, ArrayNode sampled) {
        String id = id(next);
        ObjectNode inputs = graph.putObject(id).put("class_type", "LTXVCropGuides").putObject("inputs");
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

    private static ArrayNode link(String node, int output) {
        return JsonNodeFactory.instance.arrayNode().add(node).add(output);
    }
}
