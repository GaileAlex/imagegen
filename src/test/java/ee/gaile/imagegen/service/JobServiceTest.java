package ee.gaile.imagegen.service;

import ee.gaile.imagegen.gpu.UnloadPolicy;
import ee.gaile.imagegen.job.ComfyGateway;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.MutableClock;
import ee.gaile.imagegen.model.TestCatalogue;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.storage.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceTest {

    @TempDir
    Path directory;
    @Mock
    private JobStore store;
    @Mock
    private JobQueue queue;
    @Mock
    private UnloadPolicy policy;
    @Mock
    private ComfyGateway gateway;
    private JobFiles files;
    private JobService service;

    @BeforeEach
    void setUp() {
        files = new JobFiles(directory);
        service = new JobService(TestCatalogue.get(), new JobFactory(new MutableClock()), new SourceImages(store, files),
                files, queue, policy, gateway, TestCatalogue.MAPPER);
        given(gateway.isUp()).willReturn(true);
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static String request(String model, String params) {
        return "{\"model\":\"" + model + "\",\"params\":" + params + "}";
    }

    private long fileCount() throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.count();
        }
    }

    @Test
    void anImageJobIsQueuedAfterThePolicyWasTold() {
        given(queue.submit(any())).willReturn(2);

        JobService.Accepted accepted = service.submit(7, "alex", request("muse-txt2img",
                "{\"prompt\":\"a cat\",\"seed\":5}"), null, null);

        assertThat(accepted.position()).isEqualTo(2);
        assertThat(accepted.job().getSeed()).isEqualTo(5);
        assertThat(accepted.job().getUserId()).isEqualTo(7);
        assertThat(accepted.job().getUserName()).isEqualTo("alex");
        assertThat(accepted.job().getParams()).containsEntry("prompt", "a cat").containsEntry("steps", 16);
        InOrder order = inOrder(policy, queue);
        order.verify(policy).beforeSubmit(7);
        order.verify(queue).submit(accepted.job());
    }

    @Test
    void anUnknownModelIsRefused() {
        assertThatThrownBy(() -> service.submit(1, null, request("nope", "{}"), null, null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("nope");
    }

    @Test
    void aBrokenRequestIsRefused() {
        assertThatThrownBy(() -> service.submit(1, null, "{ not json", null, null))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> service.submit(1, null, null, null, null))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> service.submit(1, null, "{\"params\":{}}", null, null))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> service.submit(1, null, "{\"model\":\"muse-txt2img\",\"params\":5}", null, null))
                .isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void invalidParamsAreRefusedBeforeAnyImageWork() throws IOException {
        assertThatThrownBy(() -> service.submit(1, null, request("muse-edit", "{\"prompt\":\"x\",\"steps\":2}"),
                "garbage".getBytes(), null)).isInstanceOf(InvalidParamsException.class).hasMessageContaining("steps");

        assertThat(fileCount()).isZero();
    }

    @Test
    void whenComfyUiIsDownNothingIsQueued() {
        given(gateway.isUp()).willReturn(false);

        assertThatThrownBy(() -> service.submit(1, null, request("muse-txt2img", "{\"prompt\":\"x\"}"), null, null))
                .isInstanceOf(ComfyUnavailableException.class);

        verify(policy, never()).beforeSubmit(anyLong());
        verify(queue, never()).submit(any());
    }

    @Test
    void anEditNeedsAnImage() {
        assertThatThrownBy(() -> service.submit(1, null, request("muse-edit", "{\"prompt\":\"x\"}"), null, null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("image");
        assertThatThrownBy(() -> service.submit(1, null, request("muse-edit", "{\"prompt\":\"x\"}"), new byte[0],
                null)).isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> service.submit(1, null, request("muse-edit", "{\"prompt\":\"x\"}"),
                "text".getBytes(), null)).isInstanceOf(InvalidParamsException.class).hasMessageContaining("unreadable");
    }

    @Test
    void anEditStoresThePhotoAtTheDerivedSize() throws IOException {
        Job job = service.submit(1, "a", request("muse-edit", "{\"prompt\":\"add a cat\"}"), png(2000, 1500), null)
                .job();

        assertThat(job.getParams()).containsEntry("width", 1168).containsEntry("height", 880);
        BufferedImage stored = ImageIO.read(files.input(job.getId()).toFile());
        assertThat(stored.getWidth()).isEqualTo(1168);
        assertThat(stored.getHeight()).isEqualTo(880);
        assertThat(files.mask(job.getId())).doesNotExist();
    }

    @Test
    void inpaintNeedsAMaskAndResizesItToThePhoto() throws IOException {
        assertThatThrownBy(() -> service.submit(1, "a", request("muse-inpaint", "{\"prompt\":\"cat\"}"),
                png(2000, 1500), null)).isInstanceOf(InvalidParamsException.class).hasMessageContaining("mask");
        assertThat(fileCount()).isZero();

        Job job = service.submit(1, "a", request("muse-inpaint", "{\"prompt\":\"cat\"}"), png(2000, 1500),
                png(100, 75)).job();

        BufferedImage mask = ImageIO.read(files.mask(job.getId()).toFile());
        assertThat(mask.getWidth()).isEqualTo(1168);
        assertThat(mask.getHeight()).isEqualTo(880);
    }

    @Test
    void aVideoKeepsThePhotoUpToTheLimitAndAddsNoSize() throws IOException {
        Job job = service.submit(1, "a", request("ltx25-nsfw-i2v", "{\"prompt\":\"walks\",\"seconds\":4}"), png(3000, 1500),
                null).job();

        assertThat(job.getKind()).isEqualTo(JobKind.VIDEO);
        assertThat(job.getParams()).doesNotContainKeys("width", "height");
        BufferedImage stored = ImageIO.read(files.input(job.getId()).toFile());
        assertThat(stored.getWidth()).isEqualTo(2048);
        assertThat(stored.getHeight()).isEqualTo(1024);
    }

    @Test
    void ltxRunsFromTheTextAloneButWanNeedsAnImage() {
        Job job = service.submit(1, "a", request("ltx25-nsfw-i2v", "{\"prompt\":\"walks\"}"), null, null).job();

        assertThat(job.getParams()).containsEntry(JobFactory.TEXT_ONLY, true);
        assertThat(files.input(job.getId())).doesNotExist();
        assertThatThrownBy(() -> service.submit(1, "a", request("wan22-i2v-fastmove", "{\"prompt\":\"walks\"}"),
                new byte[0], null)).isInstanceOf(InvalidParamsException.class).hasMessageContaining("image");
    }

    @Test
    void sourceJobIdTakesTheResultOfAnEarlierImageJob() throws IOException {
        UUID sourceId = UUID.randomUUID();
        Job source = new Job(sourceId, 1, "a", "muse-txt2img", JobKind.IMAGE, Map.of("seed", 1), 1, null);
        source.setStatus(JobStatus.DONE);
        source.setResult(new JobResult("image/png", 1, 640, 480, null));
        Files.write(files.result(sourceId, "image/png"), png(640, 480));
        given(store.find(sourceId)).willReturn(Optional.of(source));

        Job job = service.submit(1, "a", request("ltx25-nsfw-i2v", "{\"prompt\":\"x\"}").replace("}}",
                "},\"sourceJobId\":\"" + sourceId + "\"}"), null, null).job();

        assertThat(ImageIO.read(files.input(job.getId()).toFile()).getWidth()).isEqualTo(640);
    }

    @Test
    void sourceJobIdOfAnotherUserAFailedOrAMissingJobIsRefused() {
        UUID sourceId = UUID.randomUUID();
        Job source = new Job(sourceId, 2, "b", "muse-txt2img", JobKind.IMAGE, Map.of("seed", 1), 1, null);
        source.setStatus(JobStatus.DONE);
        source.setResult(new JobResult("image/png", 1, 640, 480, null));
        given(store.find(sourceId)).willReturn(Optional.of(source));
        String request = "{\"model\":\"ltx25-nsfw-i2v\",\"params\":{\"prompt\":\"x\"},\"sourceJobId\":\"" + sourceId + "\"}";

        assertThatThrownBy(() -> service.submit(1, "a", request, null, null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("sourceJobId");

        source.setStatus(JobStatus.FAILED);
        source.setResult(null);
        assertThatThrownBy(() -> service.submit(2, "b", request, null, null))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> service.submit(2, "b", request.replace(sourceId.toString(),
                UUID.randomUUID().toString()), null, null)).isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void aVideoWaitsForAPictureStillInTheQueueButAnEditDoesNot() throws IOException {
        UUID sourceId = UUID.randomUUID();
        Job source = new Job(sourceId, 1, "a", "muse-txt2img", JobKind.IMAGE, Map.of("seed", 1), 1, null);
        given(store.find(sourceId)).willReturn(Optional.of(source));

        Job video = service.submit(1, "a", "{\"model\":\"wan22-i2v-fastmove\",\"params\":{\"prompt\":\"walks\"},"
                + "\"sourceJobId\":\"" + sourceId + "\"}", null, null).job();

        assertThat(video.getSourceJobId()).isEqualTo(sourceId);
        assertThat(files.input(video.getId())).doesNotExist();
        assertThatThrownBy(() -> service.submit(1, "a", "{\"model\":\"muse-edit\",\"params\":{\"prompt\":\"x\"},"
                + "\"sourceJobId\":\"" + sourceId + "\"}", null, null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("only a video");
        assertThat(fileCount()).isZero();

        source.setStatus(JobStatus.DONE);
        source.setResult(new JobResult("image/png", 1, 3000, 1500, null));
        Files.write(files.result(sourceId, "image/png"), png(3000, 1500));
        new SourceImages(store, files).prepare(video);

        BufferedImage start = ImageIO.read(files.input(video.getId()).toFile());
        assertThat(start.getWidth()).isEqualTo(2048);
        assertThat(start.getHeight()).isEqualTo(1024);
    }

    @Test
    void aVideoWhosePictureFailedFailsAtItsStart() {
        UUID sourceId = UUID.randomUUID();
        Job source = new Job(sourceId, 1, "a", "muse-txt2img", JobKind.IMAGE, Map.of("seed", 1), 1, null);
        source.setStatus(JobStatus.CANCELLED);
        given(store.find(sourceId)).willReturn(Optional.of(source));
        Job video = new Job(UUID.randomUUID(), 1, "a", "wan22-i2v-fastmove", JobKind.VIDEO, Map.of(), 1, null);
        video.setSourceJobId(sourceId);
        SourceImages sources = new SourceImages(store, files);

        assertThatThrownBy(() -> sources.prepare(video))
                .isInstanceOf(JobFailedException.class).hasMessageContaining("CANCELLED");
        given(store.find(sourceId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> sources.prepare(video))
                .isInstanceOf(JobFailedException.class).hasMessageContaining("gone");
    }

    @Test
    void aRefusedJobLeavesNoFiles() throws IOException {
        given(queue.submit(any())).willThrow(new IllegalStateException("refused"));

        assertThatThrownBy(() -> service.submit(1, "a", request("muse-edit", "{\"prompt\":\"cat\"}"), png(400, 300),
                null)).isInstanceOf(IllegalStateException.class);

        assertThat(fileCount()).isZero();
    }
}
