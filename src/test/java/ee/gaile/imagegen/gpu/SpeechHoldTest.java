package ee.gaile.imagegen.gpu;

import ee.gaile.imagegen.job.FakeGateway;
import ee.gaile.imagegen.job.GpuHolds;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.job.TestQueues;
import ee.gaile.imagegen.job.MutableClock;
import ee.gaile.imagegen.model.TestCatalogue;
import ee.gaile.imagegen.service.JobFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechHoldTest {

    private final FakeGateway gateway = new FakeGateway();
    private final MutableClock clock = new MutableClock();
    private final GpuHolds holds = new GpuHolds(clock);
    private JobQueue queue;
    private SpeechHold speechHold;

    @BeforeEach
    void setUp() {
        queue = TestQueues.plain(gateway, clock, holds);
        queue.start();
        speechHold = new SpeechHold(holds, gateway, queue);
    }

    @AfterEach
    void tearDown() {
        queue.stop();
    }

    private Job job() {
        return new JobFactory(clock).create(UUID.randomUUID(), 1, "u", TestCatalogue.model("muse-txt2img"),
                Map.of("prompt", "x"), null, false);
    }

    @Test
    void theHoldReachesComfyUiAndTheDeadlines() {
        SpeechHold.Held held = speechHold.hold(Duration.ofSeconds(30), 0);

        assertThat(held.seconds()).isEqualTo(30.0);
        assertThat(held.freeMb()).isEqualTo(10_000);
        assertThat(gateway.holds).containsExactly("30s/0");
        assertThat(holds.held()).isTrue();
    }

    @Test
    void aLongAskIsCut() {
        assertThat(speechHold.hold(Duration.ofMinutes(10), 0).seconds()).isEqualTo(120.0);
        assertThat(speechHold.hold(Duration.ofSeconds(-5), 0).seconds()).isZero();
    }

    @Test
    void withoutRoomOnTheCardTheIdleModelIsUnloaded() throws InterruptedException {
        Job job = job();
        queue.submit(job);
        gateway.complete(gateway.awaitRunning());
        while (!queue.state().idle()) {
            Thread.sleep(10);
        }
        gateway.freeMb = 2000;

        speechHold.hold(Duration.ofSeconds(30), 4096);

        assertThat(gateway.frees).isEqualTo(1);
        assertThat(queue.state().loaded()).isFalse();
    }

    @Test
    void aRunningJobMakesTheRoomItself() throws InterruptedException {
        queue.submit(job());
        Job running = gateway.awaitRunning();
        gateway.freeMb = 2000;

        speechHold.hold(Duration.ofSeconds(30), 4096);

        assertThat(gateway.frees).isZero();
        assertThat(gateway.holds).containsExactly("30s/4096");
        gateway.complete(running);
    }

    @Test
    void enoughRoomUnloadsNothing() throws InterruptedException {
        queue.submit(job());
        gateway.complete(gateway.awaitRunning());
        while (!queue.state().idle()) {
            Thread.sleep(10);
        }

        speechHold.hold(Duration.ofSeconds(30), 4096);

        assertThat(gateway.frees).isZero();
        assertThat(queue.state().loaded()).isTrue();
    }
}
