package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.ComfyGateway;
import ee.gaile.imagegen.job.Deadline;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.Progress;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.model.ParamNames;
import ee.gaile.imagegen.model.workflow.ParamSpec;
import ee.gaile.imagegen.model.workflow.WorkflowRepository;
import ee.gaile.imagegen.model.workflow.WorkflowTemplate;
import ee.gaile.imagegen.storage.JobFiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs a job on ComfyUI: fills the workflow template with the params of the job, uploads its input images, queues the
 * prompt, follows it over the websocket (or by polling /history while the socket is down) and stores the output file
 * next to the job record. The HTTP calls themselves are {@link ComfyApi}.
 */
@Slf4j
@Component
public class ComfyUiGateway implements ComfyGateway {

    /** No event for this long: the socket may have lost the end of the prompt, so /history is asked as well */
    private static final Duration SILENCE = Duration.ofSeconds(10);
    /** Failed /history calls in a row for this long: ComfyUI is gone, the job will not finish */
    private static final Duration UNREACHABLE = Duration.ofSeconds(60);
    /** Start image of a video made from the text alone: never used, so a black 64x64 */
    private static final byte[] PLACEHOLDER = placeholder();

    private final ComfyApi comfy;
    private final WorkflowRepository workflows;
    private final JobFiles files;
    private final ComfyFiles comfyFiles;
    private final ComfyEvents events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration pollInterval;

    @Autowired
    ComfyUiGateway(ComfyApi comfy, WorkflowRepository workflows, JobFiles files, ComfyFiles comfyFiles,
                   ComfyEvents events, ObjectMapper mapper, Clock clock) {
        this(comfy, workflows, files, comfyFiles, events, mapper, clock, Duration.ofSeconds(2));
    }

    ComfyUiGateway(ComfyApi comfy, WorkflowRepository workflows, JobFiles files, ComfyFiles comfyFiles,
                   ComfyEvents events, ObjectMapper mapper, Clock clock, Duration pollInterval) {
        this.comfy = comfy;
        this.workflows = workflows;
        this.files = files;
        this.comfyFiles = comfyFiles;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    @Override
    public JobResult run(Job job, Consumer<Progress> progress, Deadline deadline) {
        return calling(() -> runPrompt(job, progress, deadline));
    }

    @Override
    public void runScene(Job job, int scene, Consumer<Progress> progress, Deadline deadline) {
        calling(() -> {
            makeScene(job, scene, progress, deadline);
            return null;
        });
    }

    @Override
    public JobResult joinScenes(Job job, Consumer<Progress> progress, Deadline deadline) {
        return calling(() -> join(job, progress, deadline));
    }

    @Override
    public void discardScenes(Job job) {
        files.deleteScenes(job.getId());
    }

    @Override
    public void interrupt() {
        comfy.interrupt();
    }

    @Override
    public void free() {
        comfy.free();
    }

    @Override
    public long hold(Duration duration, long roomMb) {
        return comfy.hold(duration, roomMb);
    }

    @Override
    public boolean isUp() {
        return comfy.isUp();
    }

    /** The failures of the HTTP calls to ComfyUI as failures of the job */
    private <T> T calling(IoCall<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            throw new JobFailedException("ComfyUI answered " + e.getStatusCode().value() + ": "
                    + ComfyApi.abbreviate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new JobFailedException("ComfyUI is not reachable: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T run() throws IOException;
    }

    /**
     * The workflow of the template with the values put into every input the params file lists for them.
     * Values without an entry in the file are ignored; inputs without a value keep what the template has.
     * A video with keepFace is rewired by {@link FaceAnchors} when its workflow can anchor the face; a video with
     * sound gets it from {@link SoundGraphs}.
     */
    ObjectNode buildWorkflow(WorkflowTemplate template, Map<String, Object> values) {
        ObjectNode graph = template.newGraph();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            ParamSpec spec = template.params().get(value.getKey());
            if (spec == null) {
                continue;
            }
            for (String target : spec.targets()) {
                int dot = target.indexOf('.');
                JsonNode node = dot > 0 ? graph.get(target.substring(0, dot)) : null;
                if (node == null || !(node.get("inputs") instanceof ObjectNode inputs)) {
                    throw new IllegalStateException("Workflow " + template.id() + " has no target " + target);
                }
                inputs.set(target.substring(dot + 1), mapper.valueToTree(value.getValue()));
            }
        }
        // a video from the text alone has no face to keep
        if (template.faceAnchor() != null && Boolean.TRUE.equals(values.get(ParamNames.KEEP_FACE))
                && !Boolean.TRUE.equals(values.get(ParamNames.TEXT_ONLY))) {
            FaceAnchors.apply(graph, template.faceAnchor(), ((Number) values.get("frames")).intValue(),
                    WorkflowValues.video(template).fps());
        }
        if (Boolean.TRUE.equals(values.get(ParamNames.SOUND))) {
            if (template.decodedNode() == null) {
                throw new IllegalStateException("Workflow " + template.id() + " cannot get a sound");
            }
            SoundGraphs.addToClip(graph, template.decodedNode(), template.outputNode(),
                    SoundGraphs.prompt((String) values.get(ParamNames.PROMPT)),
                    ((Number) values.get("seed")).longValue());
        }
        return graph;
    }

    private JobResult runPrompt(Job job, Consumer<Progress> progress, Deadline deadline) throws IOException {
        WorkflowTemplate template = workflows.get(job.getModel());
        ObjectNode graph = buildWorkflow(template, WorkflowValues.of(job, template));
        uploadInputs(job);
        try {
            Ran ran = execute(job, graph, template.outputNode(), progress, deadline);
            JobResult result = storeOutput(job, template, ran.outputs().path(template.outputNode()));
            comfy.forget(ran.promptId());
            return result;
        } finally {
            deleteInputs(job);
        }
    }

    /**
     * A scene of a long video: the workflow of the job with the values of the scene and the save of its last frames;
     * the scene and the frames go next to the job record, the frames over those of the scene before.
     */
    private void makeScene(Job job, int scene, Consumer<Progress> progress, Deadline deadline) throws IOException {
        WorkflowTemplate template = workflows.get(job.getModel());
        if (template.decodedNode() == null) {
            throw new IllegalStateException("Workflow " + template.id() + " cannot make scenes");
        }
        Map<String, Object> values = WorkflowValues.scene(job, template, scene);
        ObjectNode graph = buildWorkflow(template, values);
        String tail = SceneGraphs.addTail(graph, template.decodedNode(), template.video().tailFrames(),
                template.video().fps(), (String) values.get("filenamePrefix"));
        if (scene == 1) {
            uploadInputs(job);
        } else {
            comfy.upload(new FileSystemResource(files.tail(job.getId())));
        }
        try {
            Ran ran = execute(job, graph, template.outputNode(), progress, deadline);
            fetch(firstFile(ran.outputs().path(template.outputNode())), files.scene(job.getId(), scene));
            fetch(firstFile(ran.outputs().path(tail)), files.tail(job.getId()));
            comfy.forget(ran.promptId());
            log.info("Job {}: scene {} of {} made", job.getId(), scene, job.getScenes().size());
        } finally {
            deleteInputs(job);
            comfyFiles.delete("input", "", JobFiles.tailName(job.getId()));
        }
    }

    /**
     * All the scenes of a long video joined into the result of the job, with one sound for all of them when the job
     * asks for it (the scenes are made without); the scene files go afterwards
     */
    private JobResult join(Job job, Consumer<Progress> progress, Deadline deadline) throws IOException {
        WorkflowTemplate template = workflows.get(job.getModel());
        int count = job.getScenes().size();
        List<String> names = new ArrayList<>(count);
        try {
            for (int scene = 1; scene <= count; scene++) {
                comfy.upload(new FileSystemResource(files.scene(job.getId(), scene)));
                names.add(JobFiles.sceneName(job.getId(), scene));
            }
            double repeated = (double) template.video().tailFrames() / template.video().fps();
            ObjectNode graph = SceneGraphs.join(names, repeated, job.getId().toString());
            if (Boolean.TRUE.equals(job.getParams().get(ParamNames.SOUND))) {
                String story = job.getScenes().stream().map(Scene::modelPrompt).filter(Objects::nonNull)
                        .collect(Collectors.joining(" "));
                SoundGraphs.addToJoin(graph, SoundGraphs.prompt(story), job.getSeed());
            }
            Ran ran = execute(job, graph, SceneGraphs.JOIN_SAVE, progress, deadline);
            String contentType = "video/mp4";
            Path target = files.result(job.getId(), contentType);
            fetch(firstFile(ran.outputs().path(SceneGraphs.JOIN_SAVE)), target);
            comfy.forget(ran.promptId());
            files.deleteScenes(job.getId());
            return videoResult(job, template, contentType, target);
        } finally {
            names.forEach(name -> comfyFiles.delete("input", "", name));
        }
    }

    /**
     * Queues the graph and waits for its end. A job cancelled before is not queued at all.
     *
     * @return the prompt and its outputs by node id
     */
    private Ran execute(Job job, ObjectNode graph, String outputNode, Consumer<Progress> progress,
                        Deadline deadline) {
        if (job.isCancelRequested()) {
            throw new JobFailedException("interrupted");
        }
        PromptWatcher watcher = new PromptWatcher(outputNode, progress, clock);
        Runnable unsubscribe = events.subscribe(watcher);
        try {
            String promptId = comfy.queue(graph, events.clientId());
            watcher.bind(promptId);
            PromptWatcher.Outcome outcome = await(promptId, watcher, deadline, job::isCancelRequested);
            switch (outcome.status()) {
                case ERROR -> throw new JobFailedException(outcome.message());
                case INTERRUPTED -> throw new JobFailedException("interrupted");
                default -> {
                    // success: the output is fetched by the caller
                }
            }
            JsonNode entry = awaitHistory(promptId, outputNode, deadline);
            return new Ran(promptId, entry.path("outputs"));
        } finally {
            unsubscribe.run();
        }
    }

    private record Ran(String promptId, JsonNode outputs) {
    }

    /**
     * Waits for the end of the prompt: events of the socket normally, /history when the socket is down or quiet.
     * A cancel is sent to the prompt again every poll until it ends: ComfyUI clears the interrupt when it starts a
     * prompt (execution.py), so one that came just before the start is lost.
     */
    private PromptWatcher.Outcome await(String promptId, PromptWatcher watcher, Deadline deadline,
                                        BooleanSupplier cancelled) {
        Instant failingSince = null;
        Instant cancelSent = null;
        try {
            while (true) {
                PromptWatcher.Outcome outcome = watcher.outcome();
                if (outcome != null) {
                    return outcome;
                }
                Instant now = clock.instant();
                if (cancelled.getAsBoolean() && (cancelSent == null || !now.isBefore(cancelSent.plus(pollInterval)))) {
                    interruptQuietly(promptId);
                    cancelSent = now;
                }
                if (deadline.passed(now)) {
                    interruptQuietly(promptId);
                    throw new JobFailedException("timeout");
                }
                // a prompt that waits for the speech of the chat is silent, not hung
                Duration stalled = deadline.silence(watcher.lastEvent(), now);
                // Without the socket no event can come: the silence says nothing then
                if (events.isConnected() && stalled.compareTo(deadline.stall()) > 0) {
                    interruptQuietly(promptId);
                    throw new JobFailedException("timeout: ComfyUI reported nothing for " + stalled.toMinutes()
                            + " min");
                }
                boolean quiet = Duration.between(watcher.lastEvent(), now).compareTo(SILENCE) > 0;
                if (!events.isConnected() || quiet) {
                    try {
                        PromptWatcher.Outcome fromHistory = outcomeFromHistory(promptId);
                        failingSince = null;
                        if (fromHistory != null) {
                            watcher.finish(fromHistory);
                            continue;
                        }
                    } catch (RestClientException e) {
                        failingSince = failingSince != null ? failingSince : now;
                        if (Duration.between(failingSince, now).compareTo(UNREACHABLE) > 0) {
                            throw new JobFailedException("ComfyUI stopped answering", e);
                        }
                    }
                }
                watcher.await(pollInterval.toMillis());
            }
        } catch (InterruptedException e) {
            // The service is stopping: the prompt must not go on with nobody to collect it
            Thread.currentThread().interrupt();
            interruptQuietly(promptId);
            throw new JobFailedException("interrupted", e);
        }
    }

    /**
     * @return null while the prompt is not finished in /history
     */
    private PromptWatcher.Outcome outcomeFromHistory(String promptId) {
        JsonNode entry = comfy.history(promptId);
        if (entry == null) {
            return null;
        }
        JsonNode status = entry.path("status");
        for (JsonNode message : status.path("messages")) {
            String type = message.path(0).asString("");
            if (type.equals("execution_interrupted")) {
                return new PromptWatcher.Outcome(PromptWatcher.Status.INTERRUPTED, "interrupted");
            }
            if (type.equals("execution_error")) {
                String text = message.path(1).path("exception_message").asString("").strip();
                return new PromptWatcher.Outcome(PromptWatcher.Status.ERROR,
                        text.isEmpty() ? "ComfyUI reported an error" : text);
            }
        }
        if ("error".equals(status.path("status_str").asString(""))) {
            return new PromptWatcher.Outcome(PromptWatcher.Status.ERROR, "ComfyUI reported an error");
        }
        if (status.path("completed").asBoolean(false)) {
            return new PromptWatcher.Outcome(PromptWatcher.Status.SUCCESS, null);
        }
        return null;
    }

    /**
     * ComfyUI writes the history entry a moment after it announces the end of the prompt, so the entry is asked for
     * until it has the output.
     */
    private JsonNode awaitHistory(String promptId, String outputNode, Deadline deadline) {
        try {
            while (true) {
                JsonNode entry = comfy.history(promptId);
                if (entry != null && entry.path("outputs").has(outputNode)) {
                    return entry;
                }
                if (deadline.passed(clock.instant())) {
                    throw new JobFailedException("ComfyUI returned no output");
                }
                Thread.sleep(pollInterval.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailedException("interrupted", e);
        }
    }

    private void uploadInputs(Job job) {
        if (job.getKind() == JobKind.IMAGE) {
            return;
        }
        Path input = files.input(job.getId());
        if (Boolean.TRUE.equals(job.getParams().get(ParamNames.TEXT_ONLY))) {
            // The workflow skips the start image, but ComfyUI still wants the file of its LoadImage node
            comfy.upload(new ByteArrayResource(PLACEHOLDER) {
                @Override
                public String getFilename() {
                    return input.getFileName().toString();
                }
            });
            return;
        }
        comfy.upload(new FileSystemResource(input));
        if (job.getKind() == JobKind.INPAINT) {
            comfy.upload(new FileSystemResource(files.mask(job.getId())));
        }
    }

    /** The uploaded photo and mask have no use in ComfyUI after the prompt, whatever its end */
    private void deleteInputs(Job job) {
        if (job.getKind() != JobKind.IMAGE) {
            comfyFiles.delete("input", "", JobFiles.inputName(job.getId()));
            comfyFiles.delete("input", "", JobFiles.maskName(job.getId()));
        }
    }

    /**
     * The first file the output node produced: SaveImage lists "images", SaveVideo lists its video under "images"
     * too (with "animated"), other nodes use "gifs" or "videos"; the code does not depend on the key.
     */
    private JobResult storeOutput(Job job, WorkflowTemplate template, JsonNode output) throws IOException {
        JsonNode file = firstFile(output);
        if (file == null) {
            throw new JobFailedException("ComfyUI returned no output");
        }
        String contentType = contentType(file.path("filename").asString());
        Path target = files.result(job.getId(), contentType);
        fetch(file, target);

        if (job.getKind() == JobKind.VIDEO) {
            return videoResult(job, template, contentType, target);
        }
        BufferedImage image = ImageIO.read(target.toFile());
        if (image == null) {
            throw new JobFailedException("The result of ComfyUI is not an image");
        }
        return new JobResult(contentType, Files.size(target), image.getWidth(), image.getHeight(), null);
    }

    private static JobResult videoResult(Job job, WorkflowTemplate template, String contentType, Path file)
            throws IOException {
        int[] size = WorkflowValues.videoSize(job, template);
        return new JobResult(contentType, Files.size(file), size[0], size[1], (double) WorkflowValues.seconds(job));
    }

    /** Downloads an output file of ComfyUI to the target and deletes it from the output folder */
    private void fetch(JsonNode file, Path target) throws IOException {
        if (file == null) {
            throw new JobFailedException("ComfyUI returned no output");
        }
        String filename = file.path("filename").asString();
        String subfolder = file.path("subfolder").asString("");
        String type = file.path("type").asString("output");
        comfy.download(filename, subfolder, type, target);
        comfyFiles.delete(type, subfolder, filename);
    }

    private static JsonNode firstFile(JsonNode output) {
        for (Map.Entry<String, JsonNode> entry : output.properties()) {
            for (JsonNode candidate : entry.getValue()) {
                if (candidate.isObject() && candidate.has("filename")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String contentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        throw new JobFailedException("Unexpected output file " + filename);
    }

    /** Stops this prompt, if ComfyUI runs it; a failure is only logged, the job ends anyway */
    private void interruptQuietly(String promptId) {
        try {
            comfy.interrupt(promptId);
        } catch (RuntimeException e) {
            log.warn("Could not interrupt ComfyUI: {}", e.getMessage());
        }
    }

    private static byte[] placeholder() {
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", png);
            return png.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
