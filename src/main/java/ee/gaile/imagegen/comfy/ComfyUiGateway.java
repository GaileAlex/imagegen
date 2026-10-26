package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.ComfyGateway;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.Progress;
import ee.gaile.imagegen.service.WorkflowValues;
import ee.gaile.imagegen.model.workflow.ParamSpec;
import ee.gaile.imagegen.model.workflow.WorkflowRepository;
import ee.gaile.imagegen.model.workflow.WorkflowTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a job on ComfyUI: fills the workflow template with the params of the job, uploads its input images, queues the
 * prompt, follows it over the websocket (or by polling /history while the socket is down) and stores the output file
 * next to the job record.
 */
@Slf4j
@Component
public class ComfyUiGateway implements ComfyGateway {

    /** No event for this long: the socket may have lost the end of the prompt, so /history is asked as well */
    private static final Duration SILENCE = Duration.ofSeconds(10);
    /** Failed /history calls in a row for this long: ComfyUI is gone, the job will not finish */
    private static final Duration UNREACHABLE = Duration.ofSeconds(60);
    private static final int MAX_ERROR_BODY = 300;

    private final RestClient client;
    private final RestClient probeClient;
    private final WorkflowRepository workflows;
    private final JobFiles files;
    private final ComfyFiles comfyFiles;
    private final ComfyEvents events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration pollInterval;

    @Autowired
    public ComfyUiGateway(@Qualifier("comfyClient") RestClient client,
                          @Qualifier("comfyProbeClient") RestClient probeClient, WorkflowRepository workflows,
                          JobFiles files, ComfyFiles comfyFiles, ComfyEvents events, ObjectMapper mapper, Clock clock) {
        this(client, probeClient, workflows, files, comfyFiles, events, mapper, clock, Duration.ofSeconds(2));
    }

    ComfyUiGateway(RestClient client, RestClient probeClient, WorkflowRepository workflows, JobFiles files,
                   ComfyFiles comfyFiles, ComfyEvents events, ObjectMapper mapper, Clock clock, Duration pollInterval) {
        this.client = client;
        this.probeClient = probeClient;
        this.workflows = workflows;
        this.files = files;
        this.comfyFiles = comfyFiles;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    @Override
    public JobResult run(Job job, Consumer<Progress> progress, Instant deadline) {
        try {
            return runPrompt(job, progress, deadline);
        } catch (RestClientResponseException e) {
            throw new JobFailedException("ComfyUI answered " + e.getStatusCode().value() + ": "
                    + abbreviate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new JobFailedException("ComfyUI is not reachable: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void interrupt() {
        client.post().uri("/interrupt").retrieve().toBodilessEntity();
    }

    @Override
    public void free() {
        client.post().uri("/free").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("unload_models", true, "free_memory", true))
                .retrieve().toBodilessEntity();
    }

    @Override
    public boolean isUp() {
        try {
            probeClient.get().uri("/system_stats").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.debug("ComfyUI does not answer: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The workflow of the template with the values put into every input the params file lists for them.
     * Values without an entry in the file are ignored; inputs without a value keep what the template has.
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
                JsonNode node = graph.get(target.substring(0, dot));
                if (node == null || !(node.get("inputs") instanceof ObjectNode inputs)) {
                    throw new IllegalStateException("Workflow " + template.id() + " has no target " + target);
                }
                inputs.set(target.substring(dot + 1), mapper.valueToTree(value.getValue()));
            }
        }
        return graph;
    }

    private JobResult runPrompt(Job job, Consumer<Progress> progress, Instant deadline) throws IOException {
        WorkflowTemplate template = workflows.get(job.getModel());
        ObjectNode graph = buildWorkflow(template, WorkflowValues.of(job));
        uploadInputs(job);

        PromptWatcher watcher = new PromptWatcher(template.outputNode(), progress);
        Runnable unsubscribe = events.subscribe(watcher);
        try {
            String promptId = queue(graph);
            watcher.bind(promptId);
            // A cancel while the workflow was being prepared found no prompt to interrupt
            if (job.isCancelRequested()) {
                interruptQuietly();
            }
            PromptWatcher.Outcome outcome = await(promptId, watcher, deadline);
            switch (outcome.status()) {
                case ERROR -> throw new JobFailedException(outcome.message());
                case INTERRUPTED -> throw new JobFailedException("interrupted");
                default -> {
                    // success: the output is fetched below
                }
            }
            JsonNode entry = awaitHistory(promptId, template.outputNode(), deadline);
            JobResult result = storeOutput(job, entry.path("outputs").path(template.outputNode()));
            forget(promptId);
            return result;
        } finally {
            unsubscribe.run();
            deleteInputs(job);
        }
    }

    private void uploadInputs(Job job) {
        if (job.getKind() == JobKind.IMAGE) {
            return;
        }
        upload(files.input(job.getId()));
        if (job.getKind() == JobKind.INPAINT) {
            upload(files.mask(job.getId()));
        }
    }

    private void upload(Path file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new FileSystemResource(file));
        body.add("overwrite", "true");
        client.post().uri("/upload/image").contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                .retrieve().toBodilessEntity();
    }

    /** The uploaded photo and mask have no use in ComfyUI after the prompt, whatever its end */
    private void deleteInputs(Job job) {
        if (job.getKind() != JobKind.IMAGE) {
            comfyFiles.delete("input", "", files.input(job.getId()).getFileName().toString());
            comfyFiles.delete("input", "", files.mask(job.getId()).getFileName().toString());
        }
    }

    private String queue(ObjectNode graph) {
        JsonNode answer = client.post().uri("/prompt").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prompt", graph, "client_id", events.clientId()))
                .retrieve().body(JsonNode.class);
        String promptId = answer != null ? answer.path("prompt_id").asString("") : "";
        if (promptId.isEmpty()) {
            throw new JobFailedException("ComfyUI did not accept the workflow: " + abbreviate(String.valueOf(answer)));
        }
        return promptId;
    }

    /**
     * Waits for the end of the prompt: events of the socket normally, /history when the socket is down or quiet.
     */
    private PromptWatcher.Outcome await(String promptId, PromptWatcher watcher, Instant deadline) {
        Instant failingSince = null;
        try {
            while (true) {
                PromptWatcher.Outcome outcome = watcher.outcome();
                if (outcome != null) {
                    return outcome;
                }
                Instant now = clock.instant();
                if (!now.isBefore(deadline)) {
                    interruptQuietly();
                    throw new JobFailedException("timeout");
                }
                boolean quiet = Duration.between(watcher.lastEvent(), Instant.now()).compareTo(SILENCE) > 0;
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
            interruptQuietly();
            throw new JobFailedException("interrupted", e);
        }
    }

    /**
     * @return null while the prompt is not finished in /history
     */
    private PromptWatcher.Outcome outcomeFromHistory(String promptId) {
        JsonNode entry = history(promptId);
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
    private JsonNode awaitHistory(String promptId, String outputNode, Instant deadline) {
        try {
            while (true) {
                JsonNode entry = history(promptId);
                if (entry != null && entry.path("outputs").has(outputNode)) {
                    return entry;
                }
                if (!clock.instant().isBefore(deadline)) {
                    throw new JobFailedException("ComfyUI returned no output");
                }
                Thread.sleep(pollInterval.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailedException("interrupted", e);
        }
    }

    /**
     * @return the entry of the prompt, null while there is none
     */
    private JsonNode history(String promptId) {
        JsonNode root = client.get().uri("/history/{id}", promptId).retrieve().body(JsonNode.class);
        JsonNode entry = root != null ? root.get(promptId) : null;
        return entry != null && !entry.isNull() ? entry : null;
    }

    /**
     * The first file the output node produced: SaveImage lists "images", SaveVideo lists its video under "images"
     * too (with "animated"), other nodes use "gifs" or "videos"; the code does not depend on the key.
     */
    private JobResult storeOutput(Job job, JsonNode output) throws IOException {
        JsonNode file = firstFile(output);
        if (file == null) {
            throw new JobFailedException("ComfyUI returned no output");
        }
        String filename = file.path("filename").asString();
        String contentType = contentType(filename);
        Path target = files.result(job.getId(), contentType);
        String subfolder = file.path("subfolder").asString("");
        String type = file.path("type").asString("output");
        download(filename, subfolder, type, target);
        comfyFiles.delete(type, subfolder, filename);

        long size = Files.size(target);
        if (job.getKind() == JobKind.VIDEO) {
            int[] videoSize = WorkflowValues.videoSize(job);
            return new JobResult(contentType, size, videoSize[0], videoSize[1],
                    (double) WorkflowValues.seconds(job));
        }
        BufferedImage image = ImageIO.read(target.toFile());
        if (image == null) {
            throw new JobFailedException("The result of ComfyUI is not an image");
        }
        return new JobResult(contentType, size, image.getWidth(), image.getHeight(), null);
    }

    private JsonNode firstFile(JsonNode output) {
        for (Map.Entry<String, JsonNode> entry : output.properties()) {
            for (JsonNode candidate : entry.getValue()) {
                if (candidate.isObject() && candidate.has("filename")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String contentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        throw new JobFailedException("Unexpected output file " + filename);
    }

    private void download(String filename, String subfolder, String type, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        client.get().uri(uri -> uri.path("/view").queryParam("filename", filename)
                        .queryParam("subfolder", subfolder).queryParam("type", type).build())
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new JobFailedException("ComfyUI answered " + response.getStatusCode().value()
                                + " for the output " + filename);
                    }
                    Files.copy(response.getBody(), temp, StandardCopyOption.REPLACE_EXISTING);
                    return null;
                });
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The prompt stays in the memory of ComfyUI otherwise; failing to clear it is not worth failing the job */
    private void forget(String promptId) {
        try {
            client.post().uri("/history").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("delete", List.of(promptId))).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("Could not clear the history entry {}: {}", promptId, e.getMessage());
        }
    }

    private void interruptQuietly() {
        try {
            interrupt();
        } catch (RuntimeException e) {
            log.warn("Could not interrupt ComfyUI: {}", e.getMessage());
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "..." : text;
    }
}
