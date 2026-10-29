package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.idea.IdeaPrompts.Start;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.job.Storyboards;
import ee.gaile.imagegen.model.ModelCatalogue;
import ee.gaile.imagegen.model.ModelDefinition;
import ee.gaile.imagegen.service.JobFactory;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.storage.JobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The storyboard of a long video started without scenes: its prompt is the idea (a few words or a whole story), the
 * model looks at the start image of the job (written by then, also when it came from a picture job) and writes all
 * the scenes in one answer, as the prompt ideas do. The job holds the GPU, so the GPU instance is asked first; the
 * CPU one takes over when it fails.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobStoryboards implements Storyboards {

    private final ModelCatalogue catalogue;
    private final IdeaWriter writer;
    private final JobFiles files;
    private final JobStore store;

    @Override
    public List<Scene> write(Job job) {
        ModelDefinition model = catalogue.find(job.getModel())
                .orElseThrow(() -> new JobFailedException("unknown model " + job.getModel()));
        byte[] picture = Boolean.TRUE.equals(job.getParams().get(JobFactory.TEXT_ONLY)) ? null : startImage(job);
        Start start = picture != null ? Start.PICTURE : Start.TEXT;
        List<Integer> lengths = job.getScenes().stream().map(Scene::seconds).toList();
        boolean portrait = "portrait".equals(job.getParams().get("orientation"));
        String system = IdeaPrompts.storyboard(start, model.promptStyle(), lengths, portrait);
        String source = job.getSourceJobId() == null ? null
                : store.find(job.getSourceJobId()).map(PromptIdeaService::sourcePrompt).orElse(null);
        String user = IdeaPrompts.user((String) job.getParams().get("prompt"), source);
        Map<String, Object> schema = IdeaPrompts.storyboardSchema(start, model.promptStyle(), lengths.size());

        long started = System.nanoTime();
        Map<String, String> answer = answer(system, user, picture, schema);
        log.info("Job {}: storyboard of {} scenes written in {} ms", job.getId(), lengths.size(),
                (System.nanoTime() - started) / 1_000_000);
        log.debug("The storyboard: {} | {}", answer.get(IdeaPrompts.CAST), answer.get(IdeaPrompts.STORY));
        return IdeaPrompts.scenes(answer, lengths, false);
    }

    /**
     * Twice on the GPU (~20 s each; now and then an answer runs on and breaks off as JSON), then the CPU (3-4 min)
     */
    private Map<String, String> answer(String system, String user, byte[] picture, Map<String, Object> schema) {
        for (int attempt = 1; writer.hasGpu() && attempt <= 2; attempt++) {
            try {
                return writer.write(system, user, picture, schema, true);
            } catch (IdeaUnavailableException e) {
                log.warn("The storyboard failed on the GPU (attempt {}): {}", attempt, e.getMessage());
            }
        }
        try {
            return writer.write(system, user, picture, schema, false);
        } catch (IdeaUnavailableException e) {
            throw new JobFailedException("the storyboard could not be written: " + e.getMessage());
        }
    }

    private byte[] startImage(Job job) {
        try {
            return PromptIdeaService.small(Files.readAllBytes(files.input(job.getId())));
        } catch (IOException e) {
            throw new JobFailedException("the start image is gone");
        }
    }
}
