package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.model.ParamNames;
import ee.gaile.imagegen.model.workflow.VideoProfile;
import ee.gaile.imagegen.model.workflow.WorkflowTemplate;
import ee.gaile.imagegen.storage.JobFiles;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.Map;

/**
 * The values that go into the workflow of a job: its client params plus what the service sets itself (file names,
 * video size and length in frames by the "video" of the workflow). The names are those of
 * workflows/&lt;id&gt;.params.json.
 */
@UtilityClass
class WorkflowValues {

    static Map<String, Object> of(Job job, WorkflowTemplate template) {
        Map<String, Object> values = new HashMap<>(job.getParams());
        values.putAll(job.getTranslations());
        values.put("filenamePrefix", job.getId().toString());
        if (job.getKind() != JobKind.IMAGE) {
            values.put("image", JobFiles.inputName(job.getId()));
        }
        if (job.getKind() == JobKind.INPAINT) {
            values.put("mask", JobFiles.maskName(job.getId()));
        }
        if (job.getKind() == JobKind.VIDEO) {
            VideoProfile video = video(template);
            int[] size = videoSize(job, template);
            values.put("width", size[0]);
            values.put("height", size[1]);
            values.put("frames", video.fps() * seconds(job) + 1);
        }
        return values;
    }

    /**
     * The values of one scene of a long video: its prompt, its length, its own seed; the first scene starts from the
     * start image of the job (or the text alone), the next ones from the tail of the scene before. The face anchor is
     * off: it would pull every scene back to the start image. No sound, upscale or smoothing either: the join does
     * them for all the scenes.
     * A scene of a latent chain (SVI) keeps the start image in every scene, as its anchor: the scene before reaches it
     * as a latent, not as frames.
     *
     * @param template the workflow the scenes are made with
     * @param scene    1-based
     */
    static Map<String, Object> scene(Job job, WorkflowTemplate template, int scene) {
        Map<String, Object> values = of(job, template);
        Scene current = job.getScenes().get(scene - 1);
        VideoProfile video = video(template);
        values.put(ParamNames.PROMPT, current.modelPrompt());
        values.put("seed", job.getSeed() + scene - 1);
        values.put(ParamNames.KEEP_FACE, false);
        values.put(ParamNames.SOUND, false);
        // the join upscales and smooths the whole video
        values.put(ParamNames.UPSCALE, false);
        values.put(ParamNames.SMOOTH, false);
        values.put("filenamePrefix", job.getId() + "-scene" + scene);
        int frames = video.fps() * current.seconds();
        if (scene == 1) {
            values.put("frames", frames + 1);
        } else {
            values.put("frames", video.tailFrames() + frames);
            if (template.latentChain() == null) {
                values.put("image", JobFiles.tailName(job.getId()));
                values.put(ParamNames.TEXT_ONLY, false);
            }
        }
        return values;
    }

    /**
     * @return width and height of the video of the job
     */
    static int[] videoSize(Job job, WorkflowTemplate template) {
        VideoProfile video = video(template);
        boolean portrait = "portrait".equals(job.getParams().get(ParamNames.ORIENTATION));
        return portrait ? new int[]{video.height(), video.width()} : new int[]{video.width(), video.height()};
    }

    /**
     * @return width and height of the video the job gets: that of its workflow, upscaled when the job asks for it
     */
    static int[] resultSize(Job job, WorkflowTemplate template) {
        int[] size = videoSize(job, template);
        VideoProfile video = video(template);
        if (UpscaleGraphs.upscales(job.getParams(), video)) {
            return UpscaleGraphs.size(size[0], size[1], video);
        }
        return size;
    }

    static int seconds(Job job) {
        return ((Number) job.getParams().get(ParamNames.SECONDS)).intValue();
    }

    static VideoProfile video(WorkflowTemplate template) {
        if (template.video() == null) {
            throw new IllegalArgumentException("Workflow " + template.id() + " is not a video");
        }
        return template.video();
    }
}
