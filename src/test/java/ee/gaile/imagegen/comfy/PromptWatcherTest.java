package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.Progress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ee.gaile.imagegen.model.TestCatalogue.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

class PromptWatcherTest {

    private final List<Progress> progress = new ArrayList<>();
    private final PromptWatcher watcher = new PromptWatcher("9", progress::add);

    private ComfyEvent event(String type, String data) {
        return new ComfyEvent(type, MAPPER.readTree(data));
    }

    @Test
    void progressEventsBecomeSampling() {
        watcher.bind("p1");

        watcher.accept(event("progress", "{\"value\":7,\"max\":16,\"prompt_id\":\"p1\",\"node\":\"10\"}"));

        assertThat(progress).containsExactly(new Progress(Progress.Phase.SAMPLING, 7, 16));
        assertThat(watcher.outcome()).isNull();
    }

    @Test
    void theOutputNodeAfterSamplingIsDecoding() {
        watcher.bind("p1");
        watcher.accept(event("progress", "{\"value\":16,\"max\":16,\"prompt_id\":\"p1\"}"));

        watcher.accept(event("executing", "{\"node\":\"8\",\"prompt_id\":\"p1\"}"));
        assertThat(progress).hasSize(1);
        watcher.accept(event("executing", "{\"node\":\"9\",\"prompt_id\":\"p1\"}"));

        assertThat(progress.get(1)).isEqualTo(new Progress(Progress.Phase.DECODING, 0, 0));
    }

    @Test
    void theOutputNodeBeforeAnySamplingIsNotDecoding() {
        watcher.bind("p1");

        watcher.accept(event("executing", "{\"node\":\"9\",\"prompt_id\":\"p1\"}"));

        assertThat(progress).isEmpty();
    }

    @Test
    void executingNullAndExecutionSuccessEndTheJob() {
        watcher.bind("p1");
        watcher.accept(event("executing", "{\"node\":null,\"prompt_id\":\"p1\"}"));
        assertThat(watcher.outcome().status()).isEqualTo(PromptWatcher.Status.SUCCESS);

        PromptWatcher other = new PromptWatcher("9", p -> { });
        other.bind("p2");
        other.accept(event("execution_success", "{\"prompt_id\":\"p2\"}"));
        assertThat(other.outcome().status()).isEqualTo(PromptWatcher.Status.SUCCESS);
    }

    @Test
    void anErrorCarriesTheMessageOfComfyUi() {
        watcher.bind("p1");

        watcher.accept(event("execution_error",
                "{\"prompt_id\":\"p1\",\"exception_message\":\"CUDA out of memory \",\"exception_type\":\"OOM\"}"));

        assertThat(watcher.outcome()).isEqualTo(new PromptWatcher.Outcome(PromptWatcher.Status.ERROR,
                "CUDA out of memory"));
    }

    @Test
    void anInterruptIsReported() {
        watcher.bind("p1");

        watcher.accept(event("execution_interrupted", "{\"prompt_id\":\"p1\"}"));

        assertThat(watcher.outcome().status()).isEqualTo(PromptWatcher.Status.INTERRUPTED);
    }

    @Test
    void eventsOfOtherPromptsAreIgnored() {
        watcher.bind("p1");

        watcher.accept(event("progress", "{\"value\":1,\"max\":2,\"prompt_id\":\"other\"}"));
        watcher.accept(event("execution_success", "{\"prompt_id\":\"other\"}"));

        assertThat(progress).isEmpty();
        assertThat(watcher.outcome()).isNull();
    }

    @Test
    void eventsBeforeTheBindAreReplayedForThePromptOnly() {
        watcher.accept(event("progress", "{\"value\":1,\"max\":4,\"prompt_id\":\"p1\"}"));
        watcher.accept(event("progress", "{\"value\":3,\"max\":9,\"prompt_id\":\"old\"}"));

        watcher.bind("p1");

        assertThat(progress).containsExactly(new Progress(Progress.Phase.SAMPLING, 1, 4));
    }

    @Test
    void theFirstOutcomeWinsAndHistoryFillsAGap() {
        watcher.bind("p1");
        watcher.finish(new PromptWatcher.Outcome(PromptWatcher.Status.SUCCESS, null));

        watcher.accept(event("execution_error", "{\"prompt_id\":\"p1\",\"exception_message\":\"late\"}"));
        watcher.finish(new PromptWatcher.Outcome(PromptWatcher.Status.ERROR, "x"));

        assertThat(watcher.outcome().status()).isEqualTo(PromptWatcher.Status.SUCCESS);
    }
}
