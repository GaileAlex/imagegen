package ee.gaile.imagegen.job;

import ee.gaile.imagegen.config.ImagegenProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

public class JobQueueTest {

    public static final ImagegenProperties PROPERTIES = new ImagegenProperties("http://comfyui", Path.of("data"),
            Duration.ofMinutes(30), Duration.ofSeconds(150), Duration.ofDays(10), Duration.ofMinutes(15), 3, 10);

    private final FakeGateway gateway = new FakeGateway();
    private final FakeTranslator translator = new FakeTranslator();
    private final List<JobStatus> saved = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final List<Job> prepared = new CopyOnWriteArrayList<>();
    private volatile String sourceProblem;
    private JobQueue queue;

    @BeforeEach
    void setUp() {
        SourceInputs sources = job -> {
            if (sourceProblem != null) {
                throw new JobFailedException(sourceProblem);
            }
            prepared.add(job);
        };
        queue = new JobQueue(gateway, translator, sources, job -> saved.add(job.getStatus()), PROPERTIES, clock);
        queue.start();
    }

    @AfterEach
    void tearDown() {
        queue.stop();
    }

    public static Job job(long userId, String model) {
        return new Job(UUID.randomUUID(), userId, "user" + userId, model, JobKind.IMAGE, Map.of(), 1, null);
    }

    @Test
    void runsJobsOneAtATimeInOrder() throws InterruptedException {
        Job first = job(1, "first");
        Job second = job(2, "second");

        assertThat(queue.submit(first)).isZero();
        Job running = gateway.awaitRunning();
        assertThat(queue.submit(second)).isEqualTo(1);

        assertThat(running).isSameAs(first);
        assertThat(first.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(first.getProgress()).isEqualTo(Progress.LOADING);
        assertThat(queue.position(second)).isEqualTo(1);
        assertThat(gateway.started).containsExactly("first");

        gateway.complete(first);
        assertThat(gateway.awaitRunning()).isSameAs(second);
        assertThat(first.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(first.getResult().contentType()).isEqualTo("image/png");
        assertThat(first.getProgress()).isNull();
        assertThat(queue.position(second)).isZero();
        assertThat(queue.position(first)).isEqualTo(-1);

        gateway.complete(second);
        await().until(() -> second.getStatus() == JobStatus.DONE);
        assertThat(saved).containsExactly(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.DONE,
                JobStatus.RUNNING, JobStatus.DONE);
    }

    @Test
    void limitsJobsPerUserAndInTotal() throws InterruptedException {
        queue.submit(job(1, "a"));
        gateway.awaitRunning();
        queue.submit(job(1, "b"));
        queue.submit(job(1, "c"));

        assertThatThrownBy(() -> queue.submit(job(1, "d"))).isInstanceOf(TooManyJobsException.class);

        for (long user = 2; user <= 8; user++) {
            queue.submit(job(user, "x"));
        }
        assertThatThrownBy(() -> queue.submit(job(9, "y")))
                .isInstanceOf(TooManyJobsException.class)
                .hasMessageContaining("full");
    }

    @Test
    void cancelledQueuedJobNeverRuns() throws InterruptedException {
        Job first = job(1, "first");
        Job second = job(1, "second");
        queue.submit(first);
        gateway.awaitRunning();
        queue.submit(second);

        assertThat(queue.cancel(second)).isTrue();
        assertThat(second.getStatus()).isEqualTo(JobStatus.CANCELLED);

        gateway.complete(first);
        await().until(() -> first.getStatus() == JobStatus.DONE);
        assertThat(gateway.started).containsExactly("first");
        assertThat(queue.cancel(first)).isFalse();
    }

    @Test
    void cancelledRunningJobIsInterrupted() throws InterruptedException {
        Job job = job(1, "a");
        queue.submit(job);
        gateway.awaitRunning();

        assertThat(queue.cancel(job)).isTrue();

        await().until(() -> job.getStatus() == JobStatus.CANCELLED);
        assertThat(gateway.interrupts).isEqualTo(1);
        assertThat(job.getError()).isNull();
    }

    @Test
    void failedJobKeepsTheError() throws InterruptedException {
        Job job = job(1, "a");
        queue.submit(job);
        gateway.awaitRunning();

        gateway.fail(job, "out of memory");

        await().until(() -> job.getStatus() == JobStatus.FAILED);
        assertThat(job.getError()).isEqualTo("out of memory");
    }

    @Test
    void unloadsOnlyWhenIdle() throws InterruptedException {
        assertThat(queue.unloadIfIdle()).isTrue();
        assertThat(gateway.frees).isZero();

        Job job = job(1, "a");
        queue.submit(job);
        gateway.awaitRunning();
        assertThat(queue.unloadIfIdle()).isFalse();

        gateway.complete(job);
        await().until(() -> queue.state().idle());
        assertThat(queue.state().loaded()).isTrue();
        assertThat(queue.unloadIfIdle()).isTrue();
        assertThat(gateway.frees).isEqualTo(1);
        assertThat(translator.unloads).isEqualTo(1);
        assertThat(queue.state().loaded()).isFalse();
    }

    @Test
    void translatesRussianTextsBeforeComfyUi() throws InterruptedException {
        Job job = new Job(UUID.randomUUID(), 1, "user1", "a", JobKind.VIDEO,
                Map.of("prompt", "рыжий кот", "negativePrompt", "色调艳丽", "seconds", 5), 1, null);

        queue.submit(job);
        assertThat(gateway.awaitRunning()).isSameAs(job);

        assertThat(job.getTranslations()).isEqualTo(Map.of("prompt", "EN:рыжий кот"));
        assertThat(job.getProgress()).isEqualTo(Progress.LOADING);
        gateway.complete(job);
        await().until(() -> job.getStatus() == JobStatus.DONE);
    }

    @Test
    void englishTextsAreNotTranslated() throws InterruptedException {
        Job job = new Job(UUID.randomUUID(), 1, "user1", "a", JobKind.IMAGE, Map.of("prompt", "a ginger cat"), 1, null);

        queue.submit(job);
        gateway.awaitRunning();

        assertThat(translator.calls).isZero();
        assertThat(job.getTranslations()).isEmpty();
        gateway.complete(job);
    }

    @Test
    void theStartImageOfAWaitingVideoIsPreparedWhenItsTurnComes() throws InterruptedException {
        Job picture = job(1, "picture");
        Job video = new Job(UUID.randomUUID(), 1, "user1", "video", JobKind.VIDEO, Map.of(), 1, null);
        video.setSourceJobId(picture.getId());
        queue.submit(picture);
        gateway.awaitRunning();
        queue.submit(video);

        assertThat(prepared).isEmpty();
        gateway.complete(picture);

        assertThat(gateway.awaitRunning()).isSameAs(video);
        assertThat(prepared).containsExactly(video);
        gateway.complete(video);
        await().until(() -> video.getStatus() == JobStatus.DONE);
    }

    @Test
    void aVideoWithoutItsStartImageFailsWithoutComfyUi() {
        sourceProblem = "the start image job ended as FAILED";
        Job video = new Job(UUID.randomUUID(), 1, "user1", "video", JobKind.VIDEO, Map.of(), 1, null);
        video.setSourceJobId(UUID.randomUUID());

        queue.submit(video);

        await().until(() -> video.getStatus() == JobStatus.FAILED);
        assertThat(video.getError()).isEqualTo("the start image job ended as FAILED");
        assertThat(gateway.started).isEmpty();
    }

    @Test
    void failedTranslationFailsTheJobWithoutComfyUi() {
        translator.failing = true;
        Job job = new Job(UUID.randomUUID(), 1, "user1", "a", JobKind.IMAGE, Map.of("prompt", "кот"), 1, null);

        queue.submit(job);

        await().until(() -> job.getStatus() == JobStatus.FAILED);
        assertThat(job.getError()).isEqualTo("prompt translation failed");
        assertThat(gateway.started).isEmpty();
    }
}
