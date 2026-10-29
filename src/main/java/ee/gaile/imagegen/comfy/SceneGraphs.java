package ee.gaile.imagegen.comfy;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The parts of the ComfyUI graphs of a long video: the last frames of a scene saved as an animated png (LoadImage
 * reads it back as a batch, and the start image node of both LTX and Wan encodes a batch as the first frames of the
 * next scene), and the join of the scenes with the core video nodes. Checked against ComfyUI 0.42 on 2026-10-29.
 */
@UtilityClass
class SceneGraphs {

    /** Ids of the tail nodes: above the templates and the face anchor nodes (100+) */
    static final String TAIL_FRAMES = "190";
    static final String TAIL_SAVE = "191";
    /** The output node of the join */
    static final String JOIN_SAVE = "301";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Adds the save of the last {@code frames} decoded frames to a scene graph.
     *
     * @param decoded the node whose output 0 is the decoded frames
     * @return the id of the node whose output is the tail file
     */
    static String addTail(ObjectNode graph, String decoded, int frames, int fps, String prefix) {
        ObjectNode pick = graph.putObject(TAIL_FRAMES).put("class_type", "ImageFromBatch").putObject("inputs");
        pick.set("image", link(decoded));
        pick.put("batch_index", -frames);
        pick.put("length", frames);
        ObjectNode save = graph.putObject(TAIL_SAVE).put("class_type", "SaveAnimatedPNG").putObject("inputs");
        save.set("images", link(TAIL_FRAMES));
        save.put("filename_prefix", prefix + "-tail");
        save.put("fps", (double) fps);
        // lossless as every png; 4 keeps the save of 17 frames at ~1216x704 within a few seconds
        save.put("compress_level", 4);
        return TAIL_SAVE;
    }

    /**
     * The scene files from the input folder joined into one mp4: the first whole, every next one without its first
     * {@code repeatedSeconds} (the tail of the scene before, which it starts from). The sound goes along.
     */
    static ObjectNode join(List<String> scenes, double repeatedSeconds, String prefix) {
        ObjectNode graph = NODES.objectNode();
        ObjectNode concat = NODES.objectNode();
        for (int i = 0; i < scenes.size(); i++) {
            String load = String.valueOf(100 + i);
            graph.putObject(load).put("class_type", "LoadVideo").putObject("inputs").put("file", scenes.get(i));
            String source = load;
            if (i > 0) {
                source = String.valueOf(200 + i);
                ObjectNode slice = graph.putObject(source).put("class_type", "Video Slice").putObject("inputs");
                slice.set("video", link(load));
                slice.put("start_time", repeatedSeconds);
                // 0 = up to the end
                slice.put("duration", 0.0);
                slice.put("strict_duration", false);
            }
            // the inputs of an autogrow input are named <input>.<prefix><n>
            concat.set("videos.video" + i, link(source));
        }
        concat.put("codec", "auto");
        graph.putObject("300").put("class_type", "ConcatenateVideo").set("inputs", concat);
        ObjectNode save = graph.putObject(JOIN_SAVE).put("class_type", "SaveVideo").putObject("inputs");
        save.set("video", link("300"));
        save.put("filename_prefix", prefix);
        save.put("format", "mp4");
        save.put("codec", "h264");
        return graph;
    }

    private static ArrayNode link(String node) {
        return NODES.arrayNode().add(node).add(0);
    }
}
