package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.model.workflow.LatentChain;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static ee.gaile.imagegen.comfy.Graphs.link;
import static ee.gaile.imagegen.comfy.Graphs.node;

/**
 * The parts of the ComfyUI graphs of a long video: the last frames of a scene saved as an animated png (LoadImage
 * reads it back as a batch, and the start image node of both LTX and Wan encodes a batch as the first frames of the
 * next scene), and the join of the scenes with the core video nodes. Checked against ComfyUI 0.42 on 2026-10-29.
 * The scenes of a latent chain (SVI, Wan) pass their latent on instead and are joined with a blend of the frames
 * they share (KJNodes).
 */
@UtilityClass
class SceneGraphs {

    /** Ids of the tail nodes: above the templates and the face anchor nodes (100+) */
    static final String TAIL_FRAMES = "190";
    static final String TAIL_SAVE = "191";
    /** The join of the scenes and its output node */
    static final String JOIN = "300";
    static final String JOIN_SAVE = "301";

    /**
     * Adds the save of the last {@code frames} decoded frames to a scene graph.
     *
     * @param decoded the node whose output 0 is the decoded frames
     * @return the id of the node whose output is the tail file
     */
    static String addTail(ObjectNode graph, String decoded, int frames, int fps, String prefix) {
        ObjectNode pick = node(graph, TAIL_FRAMES, "ImageFromBatch");
        pick.set("image", link(decoded, 0));
        pick.put("batch_index", -frames);
        pick.put("length", frames);
        ObjectNode save = node(graph, TAIL_SAVE, "SaveAnimatedPNG");
        save.set("images", link(TAIL_FRAMES, 0));
        save.put("filename_prefix", prefix + "-tail");
        save.put("fps", (double) fps);
        // lossless as every png; 4 keeps the save of 17 frames at ~1216x704 within a few seconds
        save.put("compress_level", 4);
        return TAIL_SAVE;
    }

    /**
     * Wires the latent of the scene before into a scene of a latent chain (SVI), or, for the first scene, takes the
     * chain out: no latent to load, no motion latents, only the anchor.
     *
     * @param previous the latent file of the scene before in the input folder; null for the first scene
     */
    static void chainLatent(ObjectNode graph, LatentChain chain, String previous) {
        ObjectNode into = (ObjectNode) graph.get(chain.into()).get("inputs");
        if (previous == null) {
            graph.remove(chain.load());
            into.remove("prev_samples");
            into.put("motion_latent_count", 0);
        } else {
            ((ObjectNode) graph.get(chain.load()).get("inputs")).put("latent", previous);
        }
    }

    /**
     * The scenes of a latent chain (SVI) joined into one mp4: every scene after the first starts with the last
     * {@code overlap} frames of the one before, and the two are blended over them (Kijai's SVI Pro example:
     * ImageBatchExtendWithOverlap, linear_blend), so the seam has no jump. The scenes have no sound.
     */
    static ObjectNode joinBlended(List<String> scenes, int overlap, int fps, String prefix) {
        ObjectNode graph = Graphs.graph();
        ArrayNode frames = null;
        for (int i = 0; i < scenes.size(); i++) {
            String load = String.valueOf(100 + i);
            node(graph, load, "LoadVideo").put("file", scenes.get(i));
            String parts = String.valueOf(200 + i);
            node(graph, parts, "GetVideoComponents").set("video", link(load, 0));
            if (frames == null) {
                frames = link(parts, 0);
                continue;
            }
            // above the sound nodes (400+)
            String extend = String.valueOf(600 + i);
            ObjectNode blend = node(graph, extend, "ImageBatchExtendWithOverlap");
            blend.set("source_images", frames);
            blend.set("new_images", link(parts, 0));
            blend.put("overlap", overlap);
            blend.put("overlap_side", "source");
            blend.put("overlap_mode", "linear_blend");
            // 0 the source as it was, 1 the frames it starts from, 2 the source extended by the new ones
            frames = link(extend, 2);
        }
        ObjectNode create = node(graph, JOIN, "CreateVideo");
        create.set("images", frames);
        create.put("fps", (double) fps);
        ObjectNode save = node(graph, JOIN_SAVE, "SaveVideo");
        save.set("video", link(JOIN, 0));
        save.put("filename_prefix", prefix);
        save.put("format", "mp4");
        save.put("codec", "h264");
        return graph;
    }

    /**
     * The scene files from the input folder joined into one mp4: the first whole, every next one without its first
     * {@code repeatedSeconds} (the tail of the scene before, which it starts from). The sound goes along.
     */
    static ObjectNode join(List<String> scenes, double repeatedSeconds, String prefix) {
        ObjectNode graph = Graphs.graph();
        ObjectNode concat = Graphs.graph();
        for (int i = 0; i < scenes.size(); i++) {
            String load = String.valueOf(100 + i);
            node(graph, load, "LoadVideo").put("file", scenes.get(i));
            String source = load;
            if (i > 0) {
                source = String.valueOf(200 + i);
                ObjectNode slice = node(graph, source, "Video Slice");
                slice.set("video", link(load, 0));
                slice.put("start_time", repeatedSeconds);
                // 0 = up to the end
                slice.put("duration", 0.0);
                slice.put("strict_duration", false);
            }
            // the inputs of an autogrow input are named <input>.<prefix><n>
            concat.set("videos.video" + i, link(source, 0));
        }
        concat.put("codec", "auto");
        graph.putObject(JOIN).put("class_type", "ConcatenateVideo").set("inputs", concat);
        ObjectNode save = node(graph, JOIN_SAVE, "SaveVideo");
        save.set("video", link(JOIN, 0));
        save.put("filename_prefix", prefix);
        save.put("format", "mp4");
        save.put("codec", "h264");
        return graph;
    }
}
