package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.MutableClock;
import ee.gaile.imagegen.model.TestCatalogue;
import ee.gaile.imagegen.model.workflow.WorkflowRepository;
import ee.gaile.imagegen.service.JobFactory;
import ee.gaile.imagegen.storage.JobFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static ee.gaile.imagegen.model.TestCatalogue.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Against a mock HTTP server; the websocket is "down", so the gateway polls /history (every 10 ms here).
 */
class ComfyUiGatewayTest {

    private static final String BASE = "http://comfy";
    private static final String HISTORY_DONE = """
            {"p1": {"status": {"status_str": "success", "completed": true, "messages": []},
                    "outputs": {"%s": %s}}}""";

    @TempDir
    Path directory;
    private MockRestServiceServer server;
    private ComfyUiGateway gateway;
    private JobFiles files;
    private final FakeEvents events = new FakeEvents();
    private final JobFactory factory = new JobFactory(Clock.systemUTC());
    private final List<ee.gaile.imagegen.job.Progress> progress = new ArrayList<>();
    private final Consumer<ee.gaile.imagegen.job.Progress> sink = progress::add;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        files = new JobFiles(directory);
        gateway = new ComfyUiGateway(client, client, new WorkflowRepository(MAPPER), files, new ComfyFiles(""), events, MAPPER,
                Clock.systemUTC(), Duration.ofMillis(10));
    }

    private Job job(String model, Map<String, Object> params, Dimension photo) {
        return factory.create(UUID.randomUUID(), 1, "u", TestCatalogue.model(model), params, photo);
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private Instant later() {
        return Instant.now().plusSeconds(30);
    }

    @Test
    void putsTheValuesIntoEveryTargetOfTheTemplate() {
        Job job = job("muse-txt2img", params("prompt", "a cat", "negativePrompt", "blur", "steps", 12, "cfg", 2.0,
                "width", 512, "height", 768, "seed", 99), null);
        var template = new WorkflowRepository(MAPPER).get("muse-txt2img");

        ObjectNode graph = gateway.buildWorkflow(template, ee.gaile.imagegen.service.WorkflowValues.of(job));

        assertThat(graph.at("/6/inputs/prompt").asString()).isEqualTo("a cat");
        assertThat(graph.at("/7/inputs/prompt").asString()).isEqualTo("blur");
        for (String node : new String[]{"6", "7", "10"}) {
            assertThat(graph.at("/" + node + "/inputs/steps").asInt()).isEqualTo(12);
        }
        assertThat(graph.at("/10/inputs/cfg").asDouble()).isEqualTo(2.0);
        assertThat(graph.at("/10/inputs/noise_seed").asLong()).isEqualTo(99);
        for (String target : new String[]{"/5/inputs/width", "/6/inputs/width", "/6/inputs/target_width",
                "/7/inputs/width", "/7/inputs/target_width"}) {
            assertThat(graph.at(target).asInt()).as(target).isEqualTo(512);
        }
        for (String target : new String[]{"/5/inputs/height", "/6/inputs/target_height", "/7/inputs/height"}) {
            assertThat(graph.at(target).asInt()).as(target).isEqualTo(768);
        }
        assertThat(graph.at("/9/inputs/filename_prefix").asString()).isEqualTo(job.getId().toString());
        // linked inputs and the template itself stay as they were
        assertThat(graph.at("/10/inputs/model").isArray()).isTrue();
        assertThat(template.graph().at("/6/inputs/prompt").asString()).isNotEqualTo("a cat");
    }

    @Test
    void ltxGetsFramesAndHalfSizeInTheirNodes() {
        Job job = job("ltx25-i2v", params("prompt", "walks", "seconds", 2, "orientation", "portrait", "seed", 1),
                null);
        var template = new WorkflowRepository(MAPPER).get("ltx25-i2v");

        ObjectNode graph = gateway.buildWorkflow(template, ee.gaile.imagegen.service.WorkflowValues.of(job));

        assertThat(graph.at("/11/inputs/length").asInt()).isEqualTo(49);
        assertThat(graph.at("/13/inputs/frames_number").asInt()).isEqualTo(49);
        assertThat(graph.at("/11/inputs/width").asInt()).isEqualTo(352);
        assertThat(graph.at("/11/inputs/height").asInt()).isEqualTo(608);
        assertThat(graph.at("/6/inputs/image").asString()).isEqualTo(job.getId() + ".input.png");
        assertThat(graph.at("/31/inputs/filename_prefix").asString()).isEqualTo(job.getId().toString());
        assertThat(graph.at("/12/inputs/bypass").asBoolean()).isFalse();
        assertThat(graph.at("/22/inputs/bypass").asBoolean()).isFalse();
    }

    @Test
    void wanKeepsTheFaceWithTheStartImageAsTheLastFrame() {
        var template = new WorkflowRepository(MAPPER).get("wan22-i2v");
        Job kept = job("wan22-i2v", params("prompt", "walks"), null);
        Job free = job("wan22-i2v", params("prompt", "walks", "keepFace", false), null);

        ObjectNode graph = gateway.buildWorkflow(template, ee.gaile.imagegen.service.WorkflowValues.of(kept));
        ObjectNode plain = gateway.buildWorkflow(template, ee.gaile.imagegen.service.WorkflowValues.of(free));

        assertThat(graph.at("/12/class_type").asString()).isEqualTo("WanFirstLastFrameToVideo");
        assertThat(graph.at("/12/inputs/end_image").toString()).isEqualTo("[\"11\",0]");
        assertThat(graph.at("/12/inputs/start_image").toString()).isEqualTo("[\"11\",0]");
        assertThat(plain.at("/12/class_type").asString()).isEqualTo("WanImageToVideo");
        assertThat(plain.at("/12/inputs/end_image").isMissingNode()).isTrue();
    }

    @Test
    void ltxKeepsTheFaceWithGuideFramesOnBothStages() {
        var template = new WorkflowRepository(MAPPER).get("ltx25-nsfw-i2v");
        Job job = job("ltx25-nsfw-i2v", params("prompt", "walks", "seconds", 8), null);

        ObjectNode graph = gateway.buildWorkflow(template, ee.gaile.imagegen.service.WorkflowValues.of(job));

        // stage 1: 100-102 guide the half size latent at frames 64, 128 and the last, 103 crops them off
        assertThat(graph.at("/100/inputs/latent").toString()).isEqualTo("[\"12\",0]");
        assertThat(graph.at("/100/inputs/positive").toString()).isEqualTo("[\"10\",0]");
        assertThat(graph.at("/100/inputs/frame_idx").asInt()).isEqualTo(64);
        assertThat(graph.at("/101/inputs/frame_idx").asInt()).isEqualTo(128);
        assertThat(graph.at("/102/inputs/frame_idx").asInt()).isEqualTo(-1);
        assertThat(graph.at("/14/inputs/video_latent").toString()).isEqualTo("[\"102\",2]");
        assertThat(graph.at("/15/inputs/positive").toString()).isEqualTo("[\"102\",0]");
        assertThat(graph.at("/103/class_type").asString()).isEqualTo("LTXVCropGuides");
        assertThat(graph.at("/103/inputs/latent").toString()).isEqualTo("[\"20\",0]");
        assertThat(graph.at("/21/inputs/samples").toString()).isEqualTo("[\"103\",2]");
        // stage 2: 104-106 guide the full size latent, 107 is its own guider, 108 crops before decoding
        assertThat(graph.at("/104/inputs/positive").toString()).isEqualTo("[\"103\",0]");
        assertThat(graph.at("/104/inputs/latent").toString()).isEqualTo("[\"22\",0]");
        assertThat(graph.at("/23/inputs/video_latent").toString()).isEqualTo("[\"106\",2]");
        assertThat(graph.at("/107/class_type").asString()).isEqualTo("LTXVDualCFGGuider");
        assertThat(graph.at("/107/inputs/positive").toString()).isEqualTo("[\"106\",0]");
        assertThat(graph.at("/26/inputs/guider").toString()).isEqualTo("[\"107\",0]");
        assertThat(graph.at("/108/inputs/latent").toString()).isEqualTo("[\"27\",0]");
        assertThat(graph.at("/28/inputs/samples").toString()).isEqualTo("[\"108\",2]");
        assertThat(graph.has("109")).isFalse();
    }

    @Test
    void ltxFromTheTextAloneHasNoFaceToKeep() {
        var template = new WorkflowRepository(MAPPER).get("ltx25-i2v");
        Job job = factory.create(UUID.randomUUID(), 1, "u", TestCatalogue.model("ltx25-i2v"),
                params("prompt", "walks"), null, false);

        ObjectNode graph = gateway.buildWorkflow(template, ee.gaile.imagegen.service.WorkflowValues.of(job));

        assertThat(job.getParams()).containsEntry("keepFace", true);
        assertThat(graph.has("100")).isFalse();
        assertThat(graph.at("/28/inputs/samples").toString()).isEqualTo("[\"27\",0]");
    }

    @Test
    void ltxWithoutAStartImageSkipsTheImageNodesAndUploadsAPlaceholder() {
        Job job = factory.create(UUID.randomUUID(), 1, "u", TestCatalogue.model("ltx25-i2v"),
                params("prompt", "walks", "seconds", 2), null, false);
        server.expect(requestTo(BASE + "/upload/image"))
                .andExpect(content().string(containsString(job.getId() + ".input.png")))
                .andRespond(withSuccess("{\"name\":\"x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/prompt"))
                .andExpect(jsonPath("$.prompt['12'].inputs.bypass").value(true))
                .andExpect(jsonPath("$.prompt['22'].inputs.bypass").value(true))
                .andExpect(jsonPath("$.prompt['6'].inputs.image").value(job.getId() + ".input.png"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"stop here\"}}"));

        assertThatThrownBy(() -> gateway.run(job, sink, later())).isInstanceOf(JobFailedException.class);
        assertThat(files.input(job.getId())).doesNotExist();
        server.verify();
    }

    @Test
    void runsAnImageJobAndStoresThePng() throws IOException {
        Job job = job("muse-txt2img", params("prompt", "a cat", "seed", 5), null);
        server.expect(requestTo(BASE + "/prompt")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.client_id").value("client-1"))
                .andExpect(jsonPath("$.prompt['6'].inputs.prompt").value("a cat"))
                .andExpect(jsonPath("$.prompt['10'].inputs.noise_seed").value(5))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\",\"number\":1,\"node_errors\":{}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        String done = HISTORY_DONE.formatted("9",
                "{\"images\":[{\"filename\":\"x_00001_.png\",\"subfolder\":\"sub\",\"type\":\"output\"}]}");
        // once for the poll that sees the end, once for the output
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/view?filename=x_00001_.png&subfolder=sub&type=output")))
                .andRespond(withSuccess(png(8, 6), MediaType.IMAGE_PNG));
        server.expect(requestTo(BASE + "/history")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.delete[0]").value("p1")).andRespond(withSuccess());

        JobResult result = gateway.run(job, sink, later());

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.width()).isEqualTo(8);
        assertThat(result.height()).isEqualTo(6);
        assertThat(result.durationSeconds()).isNull();
        assertThat(result.size()).isEqualTo(Files.size(files.result(job.getId(), "image/png")));
        assertThat(files.directory()).isDirectoryNotContaining("glob:**.tmp");
        server.verify();
    }

    @Test
    void uploadsThePhotoAndTheMaskBeforeQueueing() throws IOException {
        Job job = job("muse-inpaint", params("prompt", "a cat", "seed", 5), new Dimension(64, 48));
        Files.write(files.input(job.getId()), png(64, 48));
        Files.write(files.mask(job.getId()), png(64, 48));
        server.expect(requestTo(BASE + "/upload/image")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString(job.getId() + ".input.png")))
                .andExpect(content().string(containsString("overwrite")))
                .andRespond(withSuccess("{\"name\":\"x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/upload/image"))
                .andExpect(content().string(containsString(job.getId() + ".mask.png")))
                .andRespond(withSuccess("{\"name\":\"x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/prompt"))
                .andExpect(jsonPath("$.prompt['15'].inputs.image").value(job.getId() + ".input.png"))
                .andExpect(jsonPath("$.prompt['102'].inputs.image").value(job.getId() + ".mask.png"))
                .andExpect(jsonPath("$.prompt['6'].inputs.width").value(64))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Prompt outputs failed validation\"}}"));

        assertThatThrownBy(() -> gateway.run(job, sink, later())).isInstanceOf(JobFailedException.class)
                .hasMessageContaining("400").hasMessageContaining("Prompt outputs failed validation");
        server.verify();
    }

    @Test
    void anExecutionErrorInTheHistoryFailsTheJobWithItsMessage() {
        Job job = job("muse-txt2img", params("prompt", "x"), null);
        server.expect(requestTo(BASE + "/prompt")).andRespond(withSuccess("{\"prompt_id\":\"p1\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess("""
                {"p1": {"status": {"status_str": "error", "completed": false, "messages": [
                  ["execution_start", {"prompt_id": "p1"}],
                  ["execution_error", {"exception_message": "CUDA out of memory", "node_id": "10"}]]}, "outputs": {}}}""",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.run(job, sink, later())).isInstanceOf(JobFailedException.class)
                .hasMessage("CUDA out of memory");
    }

    @Test
    void anInterruptInTheHistoryFailsTheJobAsInterrupted() {
        Job job = job("muse-txt2img", params("prompt", "x"), null);
        server.expect(requestTo(BASE + "/prompt")).andRespond(withSuccess("{\"prompt_id\":\"p1\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess("""
                {"p1": {"status": {"status_str": "error", "completed": false, "messages": [
                  ["execution_interrupted", {"prompt_id": "p1"}]]}, "outputs": {}}}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.run(job, sink, later())).isInstanceOf(JobFailedException.class)
                .hasMessage("interrupted");
    }

    @Test
    void aVideoOutputIsTakenFromTheImagesKeyOfSaveVideo() throws IOException {
        Job job = job("wan22-i2v-fastmove", params("prompt", "walks", "seconds", 2, "orientation", "portrait"),
                null);
        Files.write(files.input(job.getId()), png(8, 8));
        server.expect(requestTo(BASE + "/upload/image")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/prompt"))
                .andExpect(jsonPath("$.prompt['12'].inputs.length").value(33))
                .andExpect(jsonPath("$.prompt['12'].inputs.width").value(480))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        String done = HISTORY_DONE.formatted("17",
                "{\"images\":[{\"filename\":\"wan_00001.mp4\",\"subfolder\":\"video\",\"type\":\"output\"}],"
                        + "\"animated\":[true]}");
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/view?filename=wan_00001.mp4&subfolder=video")))
                .andRespond(withSuccess(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'}, MediaType.parseMediaType("video/mp4")));
        server.expect(requestTo(BASE + "/history")).andRespond(withSuccess());

        JobResult result = gateway.run(job, sink, later());

        assertThat(result).isEqualTo(new JobResult("video/mp4", 8, 480, 832, 2.0));
        assertThat(files.result(job.getId(), "video/mp4")).exists();
    }

    @Test
    void aMissingOutputFailsTheJob() {
        Job job = job("muse-txt2img", params("prompt", "x"), null);
        server.expect(requestTo(BASE + "/prompt")).andRespond(withSuccess("{\"prompt_id\":\"p1\"}",
                MediaType.APPLICATION_JSON));
        String done = HISTORY_DONE.formatted("9", "{\"images\":[]}");
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/history/p1")).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.run(job, sink, later())).isInstanceOf(JobFailedException.class)
                .hasMessage("ComfyUI returned no output");
    }

    @Test
    void pastTheDeadlineComfyUiIsInterruptedAndTheJobTimesOut() {
        Job job = job("muse-txt2img", params("prompt", "x"), null);
        server.expect(requestTo(BASE + "/prompt")).andRespond(withSuccess("{\"prompt_id\":\"p1\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/interrupt")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());

        assertThatThrownBy(() -> gateway.run(job, sink, Instant.now().minusSeconds(1)))
                .isInstanceOf(JobFailedException.class).hasMessage("timeout");
        server.verify();
    }

    @Test
    void theProgressOfTheSocketReachesTheJob() {
        events.connected = true;
        Job job = job("muse-txt2img", params("prompt", "x"), null);
        server.expect(requestTo(BASE + "/prompt")).andRespond(withSuccess("{\"prompt_id\":\"p1\"}",
                MediaType.APPLICATION_JSON));
        // the socket reports the progress and an error, the history is never asked
        events.onSubscribe = listener -> {
            listener.accept(new ComfyEvent("progress", MAPPER.readTree("{\"value\":2,\"max\":8,\"prompt_id\":\"p1\"}")));
            listener.accept(new ComfyEvent("execution_error", MAPPER.readTree(
                    "{\"prompt_id\":\"p1\",\"exception_message\":\"boom\"}")));
        };

        assertThatThrownBy(() -> gateway.run(job, sink, later())).hasMessage("boom");

        assertThat(progress).containsExactly(new ee.gaile.imagegen.job.Progress(
                ee.gaile.imagegen.job.Progress.Phase.SAMPLING, 2, 8));
        assertThat(events.listeners).isEmpty();
        server.verify();
    }

    @Test
    void freeUnloadsEverything() {
        server.expect(requestTo(BASE + "/free")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.unload_models").value(true))
                .andExpect(jsonPath("$.free_memory").value(true)).andRespond(withSuccess());

        gateway.free();

        server.verify();
    }

    @Test
    void interruptPostsToComfyUi() {
        server.expect(requestTo(BASE + "/interrupt")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());

        gateway.interrupt();

        server.verify();
    }

    @Test
    void isUpFollowsSystemStats() {
        server.expect(requestTo(BASE + "/system_stats")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/system_stats")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(gateway.isUp()).isTrue();
        assertThat(gateway.isUp()).isFalse();
    }

    @Test
    void theClockOfTheGatewayDecidesTheDeadline() {
        // a clock far in the future: the deadline passes at once even with a deadline of "later"
        MutableClock clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer other = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        ComfyUiGateway slow = new ComfyUiGateway(client, client, new WorkflowRepository(MAPPER), files, new ComfyFiles(""), events, MAPPER,
                clock, Duration.ofMillis(10));
        other.expect(requestTo(BASE + "/prompt")).andRespond(withSuccess("{\"prompt_id\":\"p1\"}",
                MediaType.APPLICATION_JSON));
        other.expect(requestTo(BASE + "/interrupt")).andRespond(withSuccess());

        assertThatThrownBy(() -> slow.run(job("muse-txt2img", params("prompt", "x"), null), sink, clock.instant()))
                .hasMessage("timeout");
    }

    /** A websocket that is down unless a test says otherwise */
    private static class FakeEvents implements ComfyEvents {

        final List<Consumer<ComfyEvent>> listeners = new ArrayList<>();
        boolean connected;
        Consumer<Consumer<ComfyEvent>> onSubscribe = listener -> { };

        @Override
        public String clientId() {
            return "client-1";
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public Runnable subscribe(Consumer<ComfyEvent> listener) {
            listeners.add(listener);
            onSubscribe.accept(listener);
            return () -> listeners.remove(listener);
        }
    }
}
