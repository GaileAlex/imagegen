package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.MutableClock;
import ee.gaile.imagegen.job.Progress;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ee.gaile.imagegen.model.TestCatalogue.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

class PromptWatcherTest {

    private final List<Progress> progress = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final PromptWatcher watcher = new PromptWatcher("9", progress::add, clock);

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
    void theStepsOfTheOutputNodeAreTheUpscale() {
        watcher.bind("p1");
        watcher.accept(event("progress", "{\"value\":16,\"max\":16,\"prompt_id\":\"p1\",\"node\":\"10\"}"));
        watcher.accept(event("executing", "{\"node\":\"9\",\"prompt_id\":\"p1\"}"));

        watcher.accept(event("progress", "{\"value\":32,\"max\":81,\"prompt_id\":\"p1\",\"node\":\"9\"}"));

        assertThat(progress).containsExactly(new Progress(Progress.Phase.SAMPLING, 16, 16),
                new Progress(Progress.Phase.DECODING, 0, 0), new Progress(Progress.Phase.UPSCALING, 32, 81));
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

        PromptWatcher other = new PromptWatcher("9", p -> { }, Clock.systemUTC());
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

    @Test
    void onlyEventsOfThePromptShowThatItIsAlive() {
        watcher.bind("p1");
        Instant created = watcher.lastEvent();
        clock.advance(Duration.ofMinutes(1));

        // the queue status and the like have no prompt id and tell nothing of the prompt
        watcher.accept(event("status", "{\"status\":{\"exec_info\":{\"queue_remaining\":0}}}"));
        watcher.accept(event("crystools.monitor", "{\"cpu_utilization\":12}"));
        assertThat(watcher.lastEvent()).isEqualTo(created);

        // a progress without the id is one of the older ComfyUI
        watcher.accept(event("progress", "{\"value\":1,\"max\":8}"));
        assertThat(watcher.lastEvent()).isEqualTo(created.plus(Duration.ofMinutes(1)));
        assertThat(progress).containsExactly(new Progress(Progress.Phase.SAMPLING, 1, 8));
    }
}
