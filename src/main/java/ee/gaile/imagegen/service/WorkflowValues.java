package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
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
     */
    private record VideoProfile(int fps, int width, int height, boolean halfStages) {
    }

    /** Both models take frames = fps * seconds + 1 (4n+1 for Wan, 8n+1 for LTX) */
    private static final Map<String, VideoProfile> VIDEO = Map.of(
            "wan22-i2v-fastmove", new VideoProfile(16, 832, 480, false),
            "wan22-i2v", new VideoProfile(16, 832, 480, false),
            "ltx25-i2v", new VideoProfile(24, 1216, 704, true),
            "ltx25-nsfw-i2v", new VideoProfile(24, 1216, 704, true));

    public static String inputName(Job job) {
        return job.getId() + ".input.png";
    }

    public static String maskName(Job job) {
        return job.getId() + ".mask.png";
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

    private static VideoProfile profile(Job job) {
        VideoProfile profile = VIDEO.get(job.getModel());
        if (profile == null) {
            throw new IllegalArgumentException("No video profile for " + job.getModel());
        }
        return profile;
    }
}
