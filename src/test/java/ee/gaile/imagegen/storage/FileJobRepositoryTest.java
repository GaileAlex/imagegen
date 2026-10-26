package ee.gaile.imagegen.storage;

import ee.gaile.imagegen.config.ImagegenProperties;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.MutableClock;
import ee.gaile.imagegen.model.TestCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileJobRepositoryTest {

    private final MutableClock clock = new MutableClock();
    private final ImagegenProperties properties = new ImagegenProperties("http://comfyui", null, Duration.ofMinutes(30),
            Duration.ofSeconds(150), Duration.ofDays(10), Duration.ofMinutes(15), 3, 10);
    @TempDir
    Path directory;
    private JobFiles files;
    private FileJobRepository repository;

    @BeforeEach
    void setUp() {
        files = new JobFiles(directory.resolve("jobs"));
        repository = new FileJobRepository(files, properties, TestCatalogue.MAPPER, clock);
        repository.load();
    }

    private Job job(long userId, Instant createdAt) {
        return new Job(UUID.randomUUID(), userId, "user" + userId, "muse-txt2img", JobKind.IMAGE,
                Map.of("prompt", "a cat", "steps", 16, "cfg", 2.5), 77L, createdAt);
    }

    private FileJobRepository restart() {
        FileJobRepository fresh = new FileJobRepository(files, properties, TestCatalogue.MAPPER, clock);
        fresh.load();
        return fresh;
    }

    @Test
    void savesARecordAsJsonAndFindsIt() throws IOException {
        Job job = job(1, clock.instant());

        repository.save(job);

        assertThat(repository.find(job.getId())).containsSame(job);
        String json = Files.readString(files.record(job.getId()));
        assertThat(json).contains("\"jobId\"").contains("\"userId\":1").contains("\"status\":\"QUEUED\"");
        assertThat(directory.resolve("jobs")).isDirectoryNotContaining("glob:**.tmp");
    }

    @Test
    void aRestartLoadsFinishedJobsWithEverything() {
        Job job = job(1, clock.instant());
        job.setStatus(JobStatus.DONE);
        job.setStartedAt(clock.instant());
        job.setFinishedAt(clock.instant());
        job.setResult(new JobResult("video/mp4", 1234, 832, 480, 5.0));
        job.setTranslations(Map.of("prompt", "a ginger cat"));
        job.setSourceJobId(new UUID(1, 2));
        repository.save(job);

        Job loaded = restart().find(job.getId()).orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(loaded.getUserId()).isEqualTo(1);
        assertThat(loaded.getUserName()).isEqualTo("user1");
        assertThat(loaded.getModel()).isEqualTo("muse-txt2img");
        assertThat(loaded.getKind()).isEqualTo(JobKind.IMAGE);
        assertThat(loaded.getSeed()).isEqualTo(77L);
        assertThat(loaded.getParams()).containsEntry("prompt", "a cat").containsEntry("steps", 16)
                .containsEntry("cfg", 2.5);
        assertThat(loaded.getCreatedAt()).isEqualTo(job.getCreatedAt());
        assertThat(loaded.getFinishedAt()).isEqualTo(job.getFinishedAt());
        assertThat(loaded.getResult()).isEqualTo(new JobResult("video/mp4", 1234, 832, 480, 5.0));
        assertThat(loaded.getTranslations()).isEqualTo(Map.of("prompt", "a ginger cat"));
        assertThat(loaded.getSourceJobId()).isEqualTo(new UUID(1, 2));
    }

    @Test
    void queuedAndRunningJobsFailAfterARestart() {
        Job queued = job(1, clock.instant());
        Job running = job(1, clock.instant());
        running.setStatus(JobStatus.RUNNING);
        repository.save(queued);
        repository.save(running);
        clock.advance(Duration.ofMinutes(5));

        FileJobRepository fresh = restart();

        for (Job job : new Job[]{queued, running}) {
            Job loaded = fresh.find(job.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(loaded.getError()).isEqualTo("service restarted");
            assertThat(loaded.getFinishedAt()).isEqualTo(clock.instant());
        }
        // and it is written down, so the next restart sees the same
        assertThat(restart().find(queued.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void listsTheJobsOfOneUserNewestFirstWithALimit() {
        Instant now = clock.instant();
        Job oldest = job(1, now.minusSeconds(300));
        Job middle = job(1, now.minusSeconds(200));
        Job newest = job(1, now.minusSeconds(100));
        Job other = job(2, now);
        for (Job job : new Job[]{middle, oldest, other, newest}) {
            repository.save(job);
        }

        assertThat(repository.findByUser(1, 50)).containsExactly(newest, middle, oldest);
        assertThat(repository.findByUser(1, 2)).containsExactly(newest, middle);
        assertThat(repository.findByUser(2, 50)).containsExactly(other);
        assertThat(repository.findByUser(3, 50)).isEmpty();
    }

    @Test
    void theListGoesBackTenDaysOnly() {
        Job old = job(1, clock.instant().minus(Duration.ofDays(11)));
        repository.save(old);

        assertThat(repository.findByUser(1, 50)).isEmpty();
    }

    @Test
    void deleteRemovesTheRecordAndAllItsFiles() throws IOException {
        Job job = job(1, clock.instant());
        repository.save(job);
        Files.writeString(files.input(job.getId()), "i");
        Files.writeString(files.mask(job.getId()), "m");
        Files.writeString(files.result(job.getId(), "image/png"), "r");
        Job other = job(1, clock.instant());
        repository.save(other);

        repository.delete(job.getId());

        assertThat(repository.find(job.getId())).isEmpty();
        assertThat(files.directory()).isDirectoryNotContaining("glob:**" + job.getId() + "*");
        assertThat(repository.find(other.getId())).isPresent();
        assertThat(files.record(other.getId())).exists();
    }

    @Test
    void cleanupDeletesJobsFinishedMoreThanTheRetentionAgo() throws IOException {
        Job expired = job(1, clock.instant());
        expired.setStatus(JobStatus.DONE);
        expired.setFinishedAt(clock.instant());
        Job fresh = job(1, clock.instant());
        fresh.setStatus(JobStatus.DONE);
        fresh.setFinishedAt(clock.instant().plus(Duration.ofDays(5)));
        Job pending = job(1, clock.instant());
        for (Job job : new Job[]{expired, fresh, pending}) {
            repository.save(job);
        }
        Files.writeString(files.result(expired.getId(), "image/png"), "r");
        clock.advance(Duration.ofDays(10).plusMinutes(1));

        repository.cleanup();

        assertThat(repository.find(expired.getId())).isEmpty();
        assertThat(files.result(expired.getId(), "image/png")).doesNotExist();
        assertThat(repository.find(fresh.getId())).isPresent();
        assertThat(repository.find(pending.getId())).isPresent();
    }

    @Test
    void anUnreadableRecordIsSkippedNotFatal() throws IOException {
        Job good = job(1, clock.instant());
        good.setStatus(JobStatus.DONE);
        repository.save(good);
        Files.writeString(files.directory().resolve(UUID.randomUUID() + ".json"), "{ not json");

        FileJobRepository fresh = restart();

        assertThat(fresh.find(good.getId())).isPresent();
    }
}
