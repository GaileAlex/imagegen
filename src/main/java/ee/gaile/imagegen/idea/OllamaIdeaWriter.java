package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.job.GpuGuest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The idea model in the GPU instance of Ollama (cv-ollama) while no job needs the GPU, else in the CPU instance
 * (cv-ollama-cpu, next to the translation model). The answer is held to the JSON schema by Ollama itself ("format").
 */
@Slf4j
@Component
class OllamaIdeaWriter implements IdeaWriter, GpuGuest {

    private final IdeaProperties properties;
    private final ObjectMapper mapper;
    private final RestClient gpuClient;
    private final RestClient cpuClient;

    OllamaIdeaWriter(IdeaProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.gpuClient = client(properties.gpuUrl(), properties.timeout());
        this.cpuClient = client(properties.url(), properties.timeout());
    }

    @Override
    public boolean hasGpu() {
        return gpuClient != null;
    }

    @Override
    public Map<String, String> write(String system, String user, byte[] picture, Map<String, Object> schema,
                                     boolean onGpu) {
        RestClient client = onGpu ? gpuClient : cpuClient;
        if (client == null) {
            throw new IdeaUnavailableException("No idea model is set");
        }
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", user);
        if (picture != null) {
            userMessage.put("images", List.of(Base64.getEncoder().encodeToString(picture)));
        }
        Map<String, Object> request = Map.of(
                "model", properties.model(),
                "stream", false,
                "think", false,
                "keep_alive", (onGpu ? properties.gpuKeepAlive() : properties.keepAlive()).toSeconds() + "s",
                "format", schema,
                "options", Map.of("temperature", properties.temperature()),
                "messages", List.of(Map.of("role", "system", "content", system), userMessage));
        JsonNode answer;
        try {
            answer = client.post().uri("/api/chat").body(request).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("The idea model failed on the {}: {}", onGpu ? "GPU" : "CPU", e.getMessage());
            throw new IdeaUnavailableException("The idea model does not answer");
        }
        String content = answer != null ? answer.path("message").path("content").asString("") : "";
        return fields(content, schema);
    }

    /**
     * Asks only when the model is there: an unload of a model that is not loaded makes Ollama load it first, into
     * the GPU memory a job is about to load into.
     */
    @Override
    public void leaveGpu() {
        if (gpuClient == null) {
            return;
        }
        try {
            JsonNode loaded = gpuClient.get().uri("/api/ps").retrieve().body(JsonNode.class);
            boolean present = loaded != null && loaded.path("models").valueStream()
                    .anyMatch(model -> properties.model().equals(model.path("name").asString("")));
            if (present) {
                gpuClient.post().uri("/api/generate")
                        .body(Map.of("model", properties.model(), "keep_alive", 0))
                        .retrieve()
                        .toBodilessEntity();
                log.info("The idea model left the GPU");
            }
        } catch (RestClientException e) {
            log.warn("Could not unload the idea model from the GPU: {}", e.getMessage());
        }
    }

    /** The string fields the schema asks for; a missing or blank one makes the whole answer useless */
    @SuppressWarnings("unchecked")
    Map<String, String> fields(String content, Map<String, Object> schema) {
        JsonNode json;
        try {
            json = mapper.readTree(content);
        } catch (JacksonException e) {
            log.warn("The idea model answered no JSON: {}", content);
            throw new IdeaUnavailableException("The idea model answered nonsense");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String name : (List<String>) schema.get("required")) {
            String value = json.path(name).asString("").strip();
            if (value.isEmpty()) {
                log.warn("The idea model left out {}: {}", name, content);
                throw new IdeaUnavailableException("The idea model answered nonsense");
            }
            fields.put(name, value);
        }
        return fields;
    }

    /** null for an empty url: that instance is not used */
    private static RestClient client(String url, Duration timeout) {
        if (url == null || url.isBlank()) {
            return null;
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .build();
    }
}
