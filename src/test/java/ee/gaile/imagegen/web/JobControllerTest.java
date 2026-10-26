package ee.gaile.imagegen.web;

import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.Progress;
import ee.gaile.imagegen.service.ComfyUnavailableException;
import ee.gaile.imagegen.service.InvalidParamsException;
import ee.gaile.imagegen.service.JobService;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.storage.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import ee.gaile.imagegen.config.ImagegenProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import ee.gaile.imagegen.job.TooManyJobsException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
class JobControllerTest {

    /** The settings are plain properties of application.yml, not a bean of the web slice */
    @TestConfiguration
    @EnableConfigurationProperties(ImagegenProperties.class)
    static class Settings {
    }

    private static final UUID ID = UUID.fromString("3f0c0000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-10-26T12:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-10-26T12:01:05Z");

    @TempDir
    Path directory;
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private JobService service;
    @MockitoBean
    private JobStore store;
    @MockitoBean
    private JobQueue queue;
    @MockitoBean
    private JobFiles files;

    @BeforeEach
    void setUp() {
        given(files.result(any(), eq("image/png"))).willReturn(directory.resolve("result.png"));
        given(files.result(any(), eq("video/mp4"))).willReturn(directory.resolve("result.mp4"));
        given(files.input(any())).willReturn(directory.resolve("input.png"));
    }

    private Job job(long userId, JobKind kind, JobStatus status) {
        Job job = new Job(ID, userId, "alex", kind == JobKind.VIDEO ? "ltx25-i2v" : "muse-txt2img", kind,
                Map.of("prompt", "a cat", "steps", 16), 123456789L, CREATED);
        job.setStatus(status);
        if (status.isFinished()) {
            job.setStartedAt(CREATED.plusSeconds(3));
            job.setFinishedAt(FINISHED);
        }
        return job;
    }

    private Job done(long userId, JobKind kind) {
        Job job = job(userId, kind, JobStatus.DONE);
        job.setResult(kind == JobKind.VIDEO ? new JobResult("video/mp4", 8, 1216, 704, 5.0)
                : new JobResult("image/png", 8, 1024, 1024, null));
        return job;
    }

    private MockMultipartFile request(String json) {
        return new MockMultipartFile("request", "", "application/json", json.getBytes());
    }

    @Test
    void submitAnswers202WithThePositionAndTheSeed() throws Exception {
        Job job = job(7, JobKind.EDIT, JobStatus.QUEUED);
        given(service.submit(eq(7L), eq("alex"), any(), any(), any())).willReturn(new JobService.Accepted(job, 1));
        byte[] photo = {1, 2, 3};

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/jobs")
                        .file(request("{\"model\":\"muse-edit\",\"params\":{}}"))
                        .file(new MockMultipartFile("image", "a.jpg", "image/jpeg", photo))
                        .header("X-User-Id", "7").header("X-User-Name", "alex"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.seed").value(123456789));

        verify(service).submit(7L, "alex", "{\"model\":\"muse-edit\",\"params\":{}}", photo, null);
    }

    @Test
    void submitNeedsTheUserHeader() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/jobs").file(request("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_params"))
                .andExpect(jsonPath("$.message").value("The header X-User-Id is required"));

        verify(service, never()).submit(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submitNeedsTheRequestPart() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/jobs").header("X-User-Id", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_params"));
    }

    @Test
    void submitErrorsUseTheCodesOfTheContract() throws Exception {
        given(service.submit(eq(1L), any(), any(), any(), any()))
                .willThrow(new InvalidParamsException("steps must be between 8 and 40"));
        given(service.submit(eq(2L), any(), any(), any(), any())).willThrow(new TooManyJobsException("full"));
        given(service.submit(eq(3L), any(), any(), any(), any())).willThrow(new ComfyUnavailableException("down"));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/jobs").file(request("{}")).header("X-User-Id", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_params"))
                .andExpect(jsonPath("$.message").value("steps must be between 8 and 40"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/jobs").file(request("{}")).header("X-User-Id", "2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_jobs"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/jobs").file(request("{}")).header("X-User-Id", "3"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("comfyui_unavailable"));
    }

    @Test
    void getShowsTheRunningJobWithItsProgress() throws Exception {
        Job job = job(7, JobKind.IMAGE, JobStatus.RUNNING);
        job.setStartedAt(CREATED.plusSeconds(3));
        job.setProgress(new Progress(Progress.Phase.SAMPLING, 7, 16));
        given(store.find(ID)).willReturn(Optional.of(job));
        given(queue.position(job)).willReturn(0);

        mockMvc.perform(get("/api/jobs/" + ID).header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(ID.toString()))
                .andExpect(jsonPath("$.model").value("muse-txt2img"))
                .andExpect(jsonPath("$.kind").value("IMAGE"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.progress.phase").value("SAMPLING"))
                .andExpect(jsonPath("$.progress.value").value(7))
                .andExpect(jsonPath("$.progress.max").value(16))
                .andExpect(jsonPath("$.params.prompt").value("a cat"))
                .andExpect(jsonPath("$.seed").value(123456789))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T12:00:00Z"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-26T12:00:03Z"))
                .andExpect(jsonPath("$.finishedAt").value((Object) null))
                .andExpect(jsonPath("$.expiresAt").value((Object) null))
                .andExpect(jsonPath("$.error").value((Object) null))
                .andExpect(jsonPath("$.result").value((Object) null));
    }

    @Test
    void getShowsAFinishedJobWithResultAndExpiry() throws Exception {
        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.VIDEO)));

        mockMvc.perform(get("/api/jobs/" + ID).header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.position").value((Object) null))
                .andExpect(jsonPath("$.finishedAt").value("2026-10-26T12:01:05Z"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-05T12:01:05Z"))
                .andExpect(jsonPath("$.result.contentType").value("video/mp4"))
                .andExpect(jsonPath("$.result.width").value(1216))
                .andExpect(jsonPath("$.result.durationSeconds").value(5.0));
    }

    @Test
    void getOfAnotherUsersOrAnUnknownJobIs404() throws Exception {
        given(store.find(ID)).willReturn(Optional.of(job(8, JobKind.IMAGE, JobStatus.QUEUED)));

        mockMvc.perform(get("/api/jobs/" + ID).header("X-User-Id", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID()).header("X-User-Id", "7"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getNeedsTheUserHeaderAndAValidId() throws Exception {
        mockMvc.perform(get("/api/jobs/" + ID)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/jobs/not-a-uuid").header("X-User-Id", "7")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_params"));
    }

    @Test
    void listUsesTheLimitAndShowsTheHistory() throws Exception {
        given(store.findByUser(eq(7L), anyInt())).willReturn(List.of(done(7, JobKind.IMAGE)));

        mockMvc.perform(get("/api/jobs").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(ID.toString()));
        verify(store).findByUser(7, 50);

        mockMvc.perform(get("/api/jobs?limit=5").header("X-User-Id", "7")).andExpect(status().isOk());
        verify(store).findByUser(7, 5);
        mockMvc.perform(get("/api/jobs?limit=100000").header("X-User-Id", "7")).andExpect(status().isOk());
        verify(store).findByUser(7, 200);
    }

    @Test
    void resultIsInlineByDefaultAndAttachmentOnDownload() throws Exception {
        Files.write(directory.resolve("result.png"), new byte[]{9, 8, 7, 6});
        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.IMAGE)));

        mockMvc.perform(get("/api/jobs/" + ID + "/result").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{9, 8, 7, 6}))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"muse-txt2img-20261026-120105-123456789.png\""));
        mockMvc.perform(get("/api/jobs/" + ID + "/result?download=true").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"muse-txt2img-20261026-120105-123456789.png\""));
    }

    @Test
    void aVideoResultSupportsRange() throws Exception {
        Files.write(directory.resolve("result.mp4"), "0123456789".getBytes());
        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.VIDEO)));

        mockMvc.perform(get("/api/jobs/" + ID + "/result").header("X-User-Id", "7").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(content().contentType(MediaType.parseMediaType("video/mp4")))
                .andExpect(content().string("2345"));
    }

    @Test
    void resultIs404UnlessDoneAndOwnedAndPresent() throws Exception {
        given(store.find(ID)).willReturn(Optional.of(job(7, JobKind.IMAGE, JobStatus.RUNNING)));
        mockMvc.perform(get("/api/jobs/" + ID + "/result").header("X-User-Id", "7")).andExpect(status().isNotFound());

        given(store.find(ID)).willReturn(Optional.of(done(8, JobKind.IMAGE)));
        mockMvc.perform(get("/api/jobs/" + ID + "/result").header("X-User-Id", "7")).andExpect(status().isNotFound());

        // done, but the file was deleted
        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.IMAGE)));
        mockMvc.perform(get("/api/jobs/" + ID + "/result").header("X-User-Id", "7")).andExpect(status().isNotFound());
    }

    @Test
    void inputIsThePngOfAnEditAndMissingForAnImageJob() throws Exception {
        Files.write(directory.resolve("input.png"), new byte[]{1, 2});
        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.EDIT)));

        mockMvc.perform(get("/api/jobs/" + ID + "/input").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2}));

        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.IMAGE)));
        mockMvc.perform(get("/api/jobs/" + ID + "/input").header("X-User-Id", "7")).andExpect(status().isNotFound());
    }

    @Test
    void inputOfAnotherUserIs404() throws Exception {
        Files.write(directory.resolve("input.png"), new byte[]{1, 2});
        given(store.find(ID)).willReturn(Optional.of(done(8, JobKind.EDIT)));

        mockMvc.perform(get("/api/jobs/" + ID + "/input").header("X-User-Id", "7")).andExpect(status().isNotFound());
    }

    @Test
    void deleteCancelsAPendingJob() throws Exception {
        Job job = job(7, JobKind.IMAGE, JobStatus.QUEUED);
        given(store.find(ID)).willReturn(Optional.of(job));
        given(queue.cancel(job)).willReturn(true);

        mockMvc.perform(delete("/api/jobs/" + ID).header("X-User-Id", "7")).andExpect(status().isNoContent());

        verify(queue).cancel(job);
        verify(store, never()).delete(any());
    }

    @Test
    void deleteRemovesAFinishedJobAndItsFiles() throws Exception {
        given(store.find(ID)).willReturn(Optional.of(done(7, JobKind.IMAGE)));

        mockMvc.perform(delete("/api/jobs/" + ID).header("X-User-Id", "7")).andExpect(status().isNoContent());

        verify(store).delete(ID);
        verify(queue, never()).cancel(any());
    }

    @Test
    void deleteOfAnotherUsersJobIs404AndChangesNothing() throws Exception {
        given(store.find(ID)).willReturn(Optional.of(done(8, JobKind.IMAGE)));

        mockMvc.perform(delete("/api/jobs/" + ID).header("X-User-Id", "7")).andExpect(status().isNotFound());

        verify(store, never()).delete(any());
        assertThat(directory).isDirectory();
    }

    @Test
    void deleteOfAJobThatFinishedMeanwhileDeletesIt() throws Exception {
        Job job = job(7, JobKind.IMAGE, JobStatus.RUNNING);
        given(store.find(ID)).willReturn(Optional.of(job));
        given(queue.cancel(job)).willAnswer(invocation -> {
            job.setStatus(JobStatus.DONE);
            return false;
        });

        mockMvc.perform(delete("/api/jobs/" + ID).header("X-User-Id", "7")).andExpect(status().isNoContent());

        verify(store).delete(ID);
    }
}
