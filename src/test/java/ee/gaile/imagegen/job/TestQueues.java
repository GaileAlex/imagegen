package ee.gaile.imagegen.job;

import java.time.Clock;
import java.util.List;

/** A queue for the tests outside this package: no sources, records, GPU guest nor storyboards to speak of */
public final class TestQueues {

    private TestQueues() {
    }

    public static JobQueue plain(ComfyGateway gateway, Clock clock, GpuHolds holds) {
        PromptTranslator translator = new FakeTranslator();
        JobRepository repository = job -> {
        };
        GpuGuest guest = () -> {
        };
        JobRunner runner = new JobRunner(gateway, translator, job -> {
        }, repository, guest, job -> List.of());
        return new JobQueue(gateway, translator, repository, JobQueueTest.PROPERTIES, clock, guest, holds, runner);
    }
}
