package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.Scene;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The values that go into the workflow of a job: its client params plus what the service sets itself (file names,
 * video size and length in frames). The names are those of workflows/&lt;id&gt;.params.json.
 */
@UtilityClass
public class WorkflowValues {

    /** Client param of a video: the start image is given to the model again later in the clip, see FaceAnchor */
    public static final String KEEP_FACE = "keepFace";
    /** Client params the workflow has no input for: they only decide other values or the shape of the graph */
    public static final Set<String> CLIENT_ONLY_PARAMS = Set.of("seconds", "orientation", KEEP_FACE);

    /**
     * @param fps        frames per second of the model
     * @param width      final size of a landscape video; portrait is the same turned around
     * @param halfStages the workflow renders the first stage at half of the final size (LTX, then upscales x2)
     * @param tailFrames the last frames of a scene the next scene of a long video starts from: ~0.6 s, so the motion
     *                   goes on and not only the pose; one more than a whole number of latent frames (8n+1 for LTX,
     *                   4n+1 for Wan), so a scene with them still has the frame count the model takes
     */
    private record VideoProfile(int fps, int width, int height, boolean halfStages, int tailFrames) {
    }

    /** Both models take frames = fps * seconds + 1 (4n+1 for Wan, 8n+1 for LTX) */
    private static final Map<String, VideoProfile> VIDEO = Map.of(
            "wan22-i2v-fastmove", new VideoProfile(16, 832, 480, false, 9),
            "wan22-i2v", new VideoProfile(16, 832, 480, false, 9),
            "ltx25-i2v", new VideoProfile(24, 1216, 704, true, 17),
            "ltx25-nsfw-i2v", new VideoProfile(24, 1216, 704, true, 17));

    public static String inputName(Job job) {
        return job.getId() + ".input.png";
    }

    public static String maskName(Job job) {
        return job.getId() + ".mask.png";
    }

    /** The last frames of the latest scene of a long video, as ComfyUI gets them */
    public static String tailName(Job job) {
        return job.getId() + ".tail.png";
    }

    /** A scene of a long video, as ComfyUI gets it for the join */
    public static String sceneName(Job job, int scene) {
        return job.getId() + ".scene" + scene + ".mp4";
    }

    public static Map<String, Object> of(Job job) {
        Map<String, Object> values = new HashMap<>(job.getParams());
        values.putAll(job.getTranslations());
        values.put("filenamePrefix", job.getId().toString());
        if (job.getKind() != JobKind.IMAGE) {
            values.put("image", inputName(job));
        }
        if (job.getKind() == JobKind.INPAINT) {
            values.put("mask", maskName(job));
        }
        if (job.getKind() == JobKind.VIDEO) {
            VideoProfile profile = profile(job);
            int[] size = videoSize(job);
            values.put("width", size[0]);
            values.put("height", size[1]);
            values.put("frames", profile.fps() * seconds(job) + 1);
            if (profile.halfStages()) {
                values.put("halfWidth", size[0] / 2);
                values.put("halfHeight", size[1] / 2);
            }
        }
        return values;
    }

    /**
     * The values of one scene of a long video: its prompt, its length, its own seed; the first scene starts from the
     * start image of the job (or the text alone), the next ones from the tail of the scene before. The face anchor is
     * off: it would pull every scene back to the start image.
     *
     * @param scene 1-based
     */
    public static Map<String, Object> scene(Job job, int scene) {
        Map<String, Object> values = of(job);
        Scene current = job.getScenes().get(scene - 1);
        VideoProfile profile = profile(job);
        values.put("prompt", current.modelPrompt());
        values.put("seed", job.getSeed() + scene - 1);
        values.put(KEEP_FACE, false);
        values.put("filenamePrefix", job.getId() + "-scene" + scene);
        int frames = profile.fps() * current.seconds();
        if (scene == 1) {
            values.put("frames", frames + 1);
        } else {
            values.put("frames", profile.tailFrames() + frames);
            values.put("image", tailName(job));
            values.put(JobFactory.TEXT_ONLY, false);
        }
        return values;
    }

    /**
     * @return width and height of the video of the job
     */
    public static int[] videoSize(Job job) {
        VideoProfile profile = profile(job);
        boolean portrait = "portrait".equals(job.getParams().get("orientation"));
        return portrait ? new int[]{profile.height(), profile.width()} : new int[]{profile.width(), profile.height()};
    }

    public static int seconds(Job job) {
        return ((Number) job.getParams().get("seconds")).intValue();
    }

    public static int fps(Job job) {
        return profile(job).fps();
    }

    public static int tailFrames(Job job) {
        return profile(job).tailFrames();
    }

    private static VideoProfile profile(Job job) {
        VideoProfile profile = VIDEO.get(job.getModel());
        if (profile == null) {
            throw new IllegalArgumentException("No video profile for " + job.getModel());
        }
        return profile;
    }
}
