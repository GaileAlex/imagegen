package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.JobFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The HTTP API of ComfyUI as the gateway uses it. The calls throw {@link RestClientException} when ComfyUI does not
 * answer or answers an error, unless they say otherwise.
 */
@Slf4j
@Component
class ComfyApi {

    private static final int MAX_ERROR_BODY = 300;

    private final RestClient client;
    private final RestClient probeClient;

    ComfyApi(@Qualifier("comfyClient") RestClient client, @Qualifier("comfyProbeClient") RestClient probeClient) {
        this.client = client;
        this.probeClient = probeClient;
    }

    /** An input file of a prompt, into the input folder of ComfyUI under the name of the resource */
    void upload(Resource image) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", image);
        body.add("overwrite", "true");
        client.post().uri("/upload/image").contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                .retrieve().toBodilessEntity();
    }

    /**
     * @param clientId the websocket that gets the events of the prompt
     * @return the id of the queued prompt
     * @throws JobFailedException ComfyUI did not accept the graph
     */
    String queue(ObjectNode graph, String clientId) {
        JsonNode answer = client.post().uri("/prompt").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prompt", graph, "client_id", clientId))
                .retrieve().body(JsonNode.class);
        String promptId = answer != null ? answer.path("prompt_id").asString("") : "";
        if (promptId.isEmpty()) {
            throw new JobFailedException("ComfyUI did not accept the workflow: " + abbreviate(String.valueOf(answer)));
        }
        return promptId;
    }

    /**
     * @return the history entry of the prompt, null while there is none
     */
    JsonNode history(String promptId) {
        JsonNode root = client.get().uri("/history/{id}", promptId).retrieve().body(JsonNode.class);
        JsonNode entry = root != null ? root.get(promptId) : null;
        return entry != null && !entry.isNull() ? entry : null;
    }

    /** An output file of ComfyUI into the target, written aside first so a broken download leaves no part of it */
    void download(String filename, String subfolder, String type, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
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
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The prompt stays in the memory of ComfyUI otherwise; failing to clear it is not worth failing the job */
    void forget(String promptId) {
        try {
            client.post().uri("/history").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("delete", List.of(promptId))).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("Could not clear the history entry {}: {}", promptId, e.getMessage());
        }
    }

    /** Stops whatever prompt ComfyUI runs now */
    void interrupt() {
        client.post().uri("/interrupt").retrieve().toBodilessEntity();
    }

    /** Stops the prompt if ComfyUI runs it now; ComfyUI ignores it for a prompt that is not running */
    void interrupt(String promptId) {
        client.post().uri("/interrupt").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prompt_id", promptId)).retrieve().toBodilessEntity();
    }

    /** Unloads every model from VRAM and frees cached memory */
    void free() {
        client.post().uri("/free").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("unload_models", true, "free_memory", true))
                .retrieve().toBodilessEntity();
    }

    /**
     * POST /gpu_yield/hold of our node (comfyui-service/gpu_yield): the prompt waits before its next layer, and gives
     * VRAM back for the room. Short timeouts: the speech must not wait for a busy ComfyUI.
     *
     * @return the VRAM free in MB, -1 when ComfyUI did not tell
     */
    long hold(Duration duration, long roomMb) {
        JsonNode state = probeClient.post().uri("/gpu_yield/hold").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("seconds", duration.toMillis() / 1000.0, "room_mb", roomMb))
                .retrieve().body(JsonNode.class);
        return state != null ? state.path("freeMb").asLong(-1) : -1;
    }

    /** ComfyUI answers /system_stats within the short timeout; never throws */
    boolean isUp() {
        try {
            probeClient.get().uri("/system_stats").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.debug("ComfyUI does not answer: {}", e.getMessage());
            return false;
        }
    }

    static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "..." : text;
    }
}
