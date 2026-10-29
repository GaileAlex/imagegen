package ee.gaile.imagegen.gpu;

import ee.gaile.imagegen.gpu.UnloadPolicy.UnloadState;
import ee.gaile.imagegen.job.FakeGateway;
import ee.gaile.imagegen.job.FakeTranslator;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static ee.gaile.imagegen.job.JobQueueTest.PROPERTIES;
import static ee.gaile.imagegen.job.JobQueueTest.job;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class UnloadPolicyTest {

    private final FakeGateway gateway = new FakeGateway();
    private final MutableClock clock = new MutableClock();
    private JobQueue queue;
    private UnloadPolicy policy;

    @BeforeEach
    void setUp() {
        queue = new JobQueue(gateway, new FakeTranslator(), job -> {
        }, job -> {
        }, PROPERTIES, clock, () -> {
        }, job -> List.of());
        queue.start();
        policy = new UnloadPolicy(queue, PROPERTIES, clock);
    }

    @AfterEach
    void tearDown() {
        queue.stop();
    }

    /** User 1 generated one picture: the model is loaded, the queue empty */
    private void generate(long userId) throws InterruptedException {
        policy.beforeSubmit(userId);
        Job job = job(userId, "muse");
        queue.submit(job);
        gateway.complete(gateway.awaitRunning());
        await().until(() -> job.getStatus() == JobStatus.DONE);
    }

    @Test
    void unloadsAfterIdleTime() throws InterruptedException {
        generate(1);

        clock.advance(Duration.ofMinutes(29));
        policy.heartbeat(1);
        policy.tick();
        assertThat(gateway.frees).isZero();
        assertThat(policy.state().idleUnloadAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(1));
        policy.heartbeat(1);
        policy.tick();
        assertThat(gateway.frees).isEqualTo(1);
        assertThat(policy.state().loaded()).isFalse();
    }

    @Test
    void lastUserLeavingUnloadsAtOnce() throws InterruptedException {
        generate(1);

        policy.leave(1);

        assertThat(gateway.frees).isEqualTo(1);
    }

    @Test
    void leavingKeepsTheModelForAnotherUserOnThePage() throws InterruptedException {
        generate(1);
        policy.heartbeat(2);

        policy.leave(1);

        assertThat(gateway.frees).isZero();
        assertThat(policy.state().presentUsers()).isEqualTo(1);
    }

    @Test
    void leavingNeverCutsOffAnotherUsersJob() throws InterruptedException {
        generate(1);
        policy.beforeSubmit(2);
        Job other = job(2, "wan");
        queue.submit(other);
        gateway.awaitRunning();

        policy.leave(1);
        policy.leave(2);
        assertThat(gateway.frees).isZero();
        assertThat(policy.state().unloadPending()).isTrue();

        gateway.complete(other);
        await().until(() -> other.getStatus() == JobStatus.DONE);
        policy.tick();
        assertThat(gateway.frees).isEqualTo(1);
        assertThat(policy.state().unloadPending()).isFalse();
    }

    @Test
    void buttonWaitsForTheQueue() throws InterruptedException {
        generate(1);
        Job job = job(1, "muse");
        queue.submit(job);
        gateway.awaitRunning();

        assertThat(policy.requestUnload()).isEqualTo(UnloadState.PENDING);

        gateway.complete(job);
        await().until(() -> job.getStatus() == JobStatus.DONE);
        policy.tick();
        assertThat(gateway.frees).isEqualTo(1);
        assertThat(policy.requestUnload()).isEqualTo(UnloadState.UNLOADED);
    }

    @Test
    void stoppedHeartbeatsCountAsLeaving() throws InterruptedException {
        generate(1);

        clock.advance(Duration.ofSeconds(100));
        policy.tick();
        assertThat(gateway.frees).isZero();

        clock.advance(Duration.ofSeconds(60));
        policy.tick();
        assertThat(gateway.frees).isEqualTo(1);
    }

    @Test
    void newJobDropsThePendingUnload() throws InterruptedException {
        generate(1);
        Job running = job(1, "muse");
        queue.submit(running);
        gateway.awaitRunning();
        policy.requestUnload();

        policy.beforeSubmit(1);

        gateway.complete(running);
        await().until(() -> running.getStatus() == JobStatus.DONE);
        policy.tick();
        assertThat(gateway.frees).isZero();
    }
}
