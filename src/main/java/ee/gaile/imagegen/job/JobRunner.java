package ee.gaile.imagegen.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One turn of a job once {@link JobQueue} has given it the GPU: the start image from its source job and the
 * translations first, then the run of ComfyUI; a long video writes its storyboard (once) and makes its next scene, or
 * the join after the last one. When a job runs and what follows its turn is the queue's business.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class JobRunner {

    private final ComfyGateway gateway;
    private final PromptTranslator translator;
    private final SourceInputs sourceInputs;
    private final JobRepository repository;
    private final GpuGuest guest;
    private final Storyboards storyboards;

    /**
     * @return the result of the job; null while scenes of a long video are left, or when the job was cancelled
     * before ComfyUI got it
     * @throws JobFailedException the turn failed (also after a cancel that interrupted ComfyUI)
     */
    JobResult run(Job job, Deadline deadline) {
        // a long video back for its next scene has its start image and translations already
        if (job.getScenesDone() == 0) {
            if (job.getSourceJobId() != null) {
                sourceInputs.prepare(job);
            }
            translate(job);
        }
        // A cancel during the translation: ComfyUI never gets the job
        if (job.isCancelRequested()) {
            return null;
        }
        return job.hasScenes() ? step(job, deadline) : gateway.run(job, job::setProgress, deadline);
    }

    /**
     * One turn of a long video: its storyboard first if the user gave no scenes, then its next scene; after the last
     * scene the join.
     *
     * @return the result after the join, null while scenes are left
     */
    private JobResult step(Job job, Deadline deadline) {
        int count = job.getScenes().size();
        if (job.getScenes().getFirst().prompt() == null) {
            writeStoryboard(job);
            // the language model took minutes: a cancel meanwhile must not start the scene
            if (job.isCancelRequested()) {
                return null;
            }
        }
        translateScenes(job);
        int scene = job.getScenesDone() + 1;
        // all made: a join that failed is tried again on its own
        if (scene <= count) {
            gateway.runScene(job, scene, progress -> job.setProgress(progress.inScene(scene, count)), deadline);
            job.setScenesDone(scene);
            job.setStepFailures(0);
            if (scene < count) {
                return null;
            }
        }
        Progress joining = new Progress(Progress.Phase.JOINING, 0, 0).inScene(count, count);
        job.setProgress(joining);
        // the join has no sampler steps to report (the sound has some: still the join); the upscale has its frames
        return gateway.joinScenes(job, progress -> job.setProgress(progress.phase() == Progress.Phase.UPSCALING
                ? progress.inScene(count, count) : joining), deadline);
    }

    private void writeStoryboard(Job job) {
        int count = job.getScenes().size();
        job.setProgress(new Progress(Progress.Phase.STORYBOARD, 0, 0).inScene(1, count));
        // the language model needs the GPU; the previous job may have left its model there
        try {
            gateway.free();
        } catch (RuntimeException e) {
            log.warn("Unload for the storyboard failed: {}", e.getMessage());
        }
        try {
            job.setScenes(List.copyOf(storyboards.write(job)));
        } finally {
            guest.leaveGpu();
        }
        // every step has its own retry
        job.setStepFailures(0);
        repository.saveQuietly(job);
        log.info("Job {}: storyboard of {} scenes written", job.getId(), count);
    }

    /**
     * On the CPU, so the GPU model of the previous job may stay loaded meanwhile. The prompt of a long video is not
     * translated: its scenes are, or the language model writes them in English.
     */
    private void translate(Job job) {
        Map<String, String> texts = PromptTranslator.cyrillicTexts(job.getParams());
        if (job.hasScenes()) {
            texts.remove("prompt");
        }
        // a long video tried again keeps the translations of its first try
        if (texts.isEmpty() || !job.getTranslations().isEmpty()) {
            return;
        }
        job.setProgress(Progress.TRANSLATING);
        Map<String, String> translations = translator.translate(texts);
        job.setProgress(Progress.LOADING);
        // no translation model: the texts go as typed
        if (translations.isEmpty()) {
            return;
        }
        job.setTranslations(Map.copyOf(translations));
        job.setStepFailures(0);
        repository.saveQuietly(job);
        log.info("Job {}: translated {}", job.getId(), job.getTranslations().keySet());
    }

    /** The scenes typed with Cyrillic in them, once */
    private void translateScenes(Job job) {
        List<Scene> scenes = new ArrayList<>(job.getScenes());
        boolean changed = false;
        for (int i = 0; i < scenes.size(); i++) {
            Scene scene = scenes.get(i);
            if (scene.english() != null || !PromptTranslator.hasCyrillic(scene.prompt())) {
                continue;
            }
            Progress phase = job.getProgress();
            job.setProgress(Progress.TRANSLATING.inScene(i + 1, scenes.size()));
            String english = translator.translate(Map.of("prompt", scene.prompt())).get("prompt");
            job.setProgress(phase);
            // null without a translation model: the scene goes as typed
            if (english != null) {
                scenes.set(i, scene.withEnglish(english));
                changed = true;
            }
        }
        if (changed) {
            job.setScenes(List.copyOf(scenes));
            job.setStepFailures(0);
            repository.saveQuietly(job);
            log.info("Job {}: scenes translated", job.getId());
        }
    }
}
