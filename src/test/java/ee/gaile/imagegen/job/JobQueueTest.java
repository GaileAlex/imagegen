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
import static org.awaitility.Awaitility.await;

public class JobQueueTest {

    public static final ImagegenProperties PROPERTIES = new ImagegenProperties("http://comfyui", Path.of("data"),
            Duration.ofMinutes(30), Duration.ofSeconds(150), Duration.ofDays(10), Duration.ofMinutes(60),
            Duration.ofMinutes(10));

    private final FakeGateway gateway = new FakeGateway();
    private final FakeTranslator translator = new FakeTranslator();
    private final List<JobStatus> saved = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final List<Job> prepared = new CopyOnWriteArrayList<>();
    // the jobs ComfyUI had started when the GPU guest left, one entry per leave
    private final List<Integer> guestLeaves = new CopyOnWriteArrayList<>();
    private final List<Job> storyboarded = new CopyOnWriteArrayList<>();
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
        Storyboards storyboards = job -> {
            storyboarded.add(job);
            return job.getScenes().stream().map(scene -> scene.withPrompt("written " + scene.seconds())).toList();
        };
        queue = new JobQueue(gateway, translator, sources, job -> saved.add(job.getStatus()), PROPERTIES, clock,
                () -> guestLeaves.add(gateway.started.size()), storyboards);
        queue.start();
    }

    @AfterEach
    void tearDown() {
        queue.stop();
    }

    public static Job job(long userId, String model) {
        return new Job(UUID.randomUUID(), userId, "user" + userId, model, JobKind.IMAGE, Map.of(), 1, null);
    }

    private static Job longVideo(long userId, String model, Scene... scenes) {
        Job job = new Job(UUID.randomUUID(), userId, "user" + userId, model, JobKind.VIDEO,
                Map.of("prompt", "a story", "seconds", 20), 1, null);
        job.setScenes(List.of(scenes));
        return job;
    }

    @Test
    void theGpuIsLentOnlyWhenNoJobRunsOrWaits() throws InterruptedException {
        Job first = job(1, "first");
        queue.submit(first);
        gateway.awaitRunning();
        assertThat(queue.lendGpu()).isFalse();

        gateway.complete(first);
        await().until(() -> first.getStatus() == JobStatus.DONE);
        assertThat(queue.lendGpu()).isTrue();
        // the model of the last job left the GPU for the guest
        assertThat(gateway.frees).isEqualTo(1);
        assertThat(queue.state().loaded()).isFalse();
        queue.returnGpu();
    }

    @Test
    void aJobWaitsForTheGuestAndTheGuestLeavesBeforeIt() throws InterruptedException {
        assertThat(queue.lendGpu()).isTrue();
        Job job = job(1, "after-guest");
        queue.submit(job);
        assertThat(queue.lendGpu()).isFalse();

        Thread.sleep(200);
        assertThat(gateway.started).isEmpty();
        queue.returnGpu();

        gateway.awaitRunning();
        assertThat(guestLeaves).containsExactly(0);
        assertThat(gateway.started).containsExactly("after-guest");
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
    void usersTakeTurnsAndTheJobsOfOneUserKeepTheirOrder() throws InterruptedException {
        Job a1 = job(1, "a1");
        Job a2 = job(1, "a2");
        Job a3 = job(1, "a3");
        Job b1 = job(2, "b1");
        Job b2 = job(2, "b2");
        Job c1 = job(3, "c1");
        queue.submit(a1);
        Job running = gateway.awaitRunning();
        queue.submit(a2);
        queue.submit(a3);
        queue.submit(b1);
        queue.submit(b2);
        queue.submit(c1);

        // users 2 and 3 were never served, user 1 just was
        assertThat(queue.position(b1)).isEqualTo(1);
        assertThat(queue.position(c1)).isEqualTo(2);
        assertThat(queue.position(a2)).isEqualTo(3);
        assertThat(queue.position(b2)).isEqualTo(4);
        assertThat(queue.position(a3)).isEqualTo(5);

        for (int i = 0; i < 5; i++) {
            gateway.complete(running);
            running = gateway.awaitRunning();
        }
        gateway.complete(running);
        await().until(() -> a3.getStatus() == JobStatus.DONE);
        assertThat(gateway.started).containsExactly("a1", "b1", "c1", "a2", "b2", "a3");
    }

    @Test
    void takesAnyNumberOfJobsAndTheNextUserWaitsForOneJobOnly() throws InterruptedException {
        queue.submit(job(1, "a0"));
        gateway.awaitRunning();
        for (int i = 1; i < 100; i++) {
            queue.submit(job(1, "a" + i));
        }
        Job other = job(2, "b");

        assertThat(queue.submit(other)).isEqualTo(100);
        assertThat(queue.position(other)).isEqualTo(1);
        assertThat(queue.state().pending()).isEqualTo(101);
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
    void aLongVideoRunsOneScenePerTurnAndOtherUsersGoInBetween() throws InterruptedException {
        Job film = longVideo(1, "film", new Scene(10, "plays", null), new Scene(10, "stands up", null));
        Job other = job(2, "other");
        queue.submit(film);
        gateway.awaitRunning();
        queue.submit(other);

        gateway.complete(film);
        assertThat(gateway.awaitRunning()).isSameAs(other);
        // back in the queue for its second scene, still RUNNING to the page
        assertThat(film.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(film.getProgress()).isEqualTo(new Progress(Progress.Phase.WAITING, 0, 0, 2, 2));
        assertThat(queue.position(film)).isEqualTo(1);

        gateway.complete(other);
        assertThat(gateway.awaitRunning()).isSameAs(film);
        assertThat(film.getProgress().scene()).isEqualTo(2);
        gateway.complete(film);

        await().until(() -> film.getStatus() == JobStatus.DONE);
        assertThat(gateway.started).containsExactly("film#scene1", "other", "film#scene2", "film#join");
        assertThat(film.getResult().contentType()).isEqualTo("video/mp4");
        assertThat(film.getProgress()).isNull();
        assertThat(storyboarded).isEmpty();
        assertThat(gateway.discarded).isEmpty();
    }

    @Test
    void aLaterJobOfTheSameUserWaitsUntilTheLongVideoIsDone() throws InterruptedException {
        Job film = longVideo(1, "film", new Scene(10, "plays", null), new Scene(10, "stands up", null));
        Job next = longVideo(1, "next", new Scene(5, null, null), new Scene(5, null, null));
        Job other = job(2, "other");
        queue.submit(film);
        gateway.awaitRunning();
        queue.submit(next);
        queue.submit(other);

        gateway.complete(film);
        assertThat(gateway.awaitRunning()).isSameAs(other);
        // the film goes on before the later job of its user
        assertThat(queue.position(film)).isEqualTo(1);
        assertThat(queue.position(next)).isEqualTo(2);

        gateway.complete(other);
        assertThat(gateway.awaitRunning()).isSameAs(film);
        gateway.complete(film);
        assertThat(gateway.awaitRunning()).isSameAs(next);
        assertThat(film.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(storyboarded).containsExactly(next);
        gateway.complete(next);
        gateway.awaitRunning();
        gateway.complete(next);

        await().until(() -> next.getStatus() == JobStatus.DONE);
        assertThat(gateway.started).containsExactly("film#scene1", "other", "film#scene2", "film#join",
                "next#scene1", "next#scene2", "next#join");
    }

    @Test
    void theStoryboardIsWrittenFirstOnTheFreedGpu() throws InterruptedException {
        Job film = longVideo(1, "film", new Scene(10, null, null), new Scene(8, null, null));
        queue.submit(film);

        assertThat(gateway.awaitRunning()).isSameAs(film);
        assertThat(storyboarded).containsExactly(film);
        assertThat(gateway.frees).isEqualTo(1);
        // the language model left the GPU before the first scene
        assertThat(guestLeaves).containsExactly(0);
        assertThat(film.getScenes()).extracting(Scene::prompt).containsExactly("written 10", "written 8");

        gateway.complete(film);
        gateway.awaitRunning();
        gateway.complete(film);
        await().until(() -> film.getStatus() == JobStatus.DONE);
        assertThat(storyboarded).hasSize(1);
    }

    @Test
    void aFailedSceneIsTriedOnceMoreThenTheVideoFails() throws InterruptedException {
        Job film = longVideo(1, "film", new Scene(10, "plays", null), new Scene(10, "stands up", null));
        queue.submit(film);
        gateway.awaitRunning();

        gateway.fail(film, "out of memory");
        gateway.awaitRunning();
        gateway.complete(film);
        gateway.awaitRunning();
        assertThat(gateway.started).containsExactly("film#scene1", "film#scene1", "film#scene2");

        gateway.fail(film, "out of memory");
        gateway.awaitRunning();
        gateway.fail(film, "out of memory");

        await().until(() -> film.getStatus() == JobStatus.FAILED);
        assertThat(film.getError()).isEqualTo("out of memory");
        assertThat(gateway.discarded).containsExactly(film);
    }

    @Test
    void aFailedJoinIsTriedAgainWithoutMakingTheScenesAgain() throws InterruptedException {
        gateway.joinFailures = 1;
        Job film = longVideo(1, "film", new Scene(10, "plays", null), new Scene(10, "stands up", null));
        queue.submit(film);
        gateway.awaitRunning();
        gateway.complete(film);
        gateway.awaitRunning();
        gateway.complete(film);

        await().until(() -> film.getStatus() == JobStatus.DONE);
        assertThat(gateway.started).containsExactly("film#scene1", "film#scene2", "film#join", "film#join");
    }

    @Test
    void aLongVideoCancelledBetweenTwoScenesLeavesNoScenes() throws InterruptedException {
        Job film = longVideo(1, "film", new Scene(10, "plays", null), new Scene(10, "stands up", null));
        Job other = job(2, "other");
        queue.submit(film);
        gateway.awaitRunning();
        queue.submit(other);
        gateway.complete(film);
        gateway.awaitRunning();

        assertThat(queue.cancel(film)).isTrue();

        assertThat(film.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(gateway.discarded).containsExactly(film);
        gateway.complete(other);
        await().until(() -> other.getStatus() == JobStatus.DONE);
        assertThat(gateway.started).containsExactly("film#scene1", "other");
    }

    @Test
    void russianScenesAreTranslatedOnceAndTheWholePromptNot() throws InterruptedException {
        Job film = longVideo(1, "film", new Scene(10, "играет на гитаре", null), new Scene(10, "stands up", null));
        queue.submit(film);
        gateway.awaitRunning();
        gateway.complete(film);
        gateway.awaitRunning();
        gateway.complete(film);

        await().until(() -> film.getStatus() == JobStatus.DONE);
        assertThat(film.getScenes()).extracting(Scene::modelPrompt)
                .containsExactly("EN:играет на гитаре", "stands up");
        assertThat(translator.calls).isEqualTo(1);
        assertThat(film.getTranslations()).isEmpty();
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
