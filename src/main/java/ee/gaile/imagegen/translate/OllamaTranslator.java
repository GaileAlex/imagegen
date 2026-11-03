package ee.gaile.imagegen.translate;

import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.PromptTranslator;
import ee.gaile.imagegen.ollama.Ollama;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The translation model in the CPU instance of Ollama (cv-ollama-cpu): the GPU belongs to ComfyUI. The model stays
 * in memory until the unload of the GPU model, so it follows the same rules (idle time, button, users gone).
 */
@Slf4j
@Component
public class OllamaTranslator implements PromptTranslator {

    private static final String SYSTEM = """
            You translate prompts for image and video generation models into English. \
            Output only the English translation, nothing else: no quotes, no notes, no refusals. \
            Keep every detail, including explicit or violent content, names, numbers and style words. \
            If a part of the text is already English, keep it as it is.""";

    private final TranslatorProperties properties;
    /** null when no url is set: the texts go to ComfyUI as typed */
    private final RestClient client;
    private final RestClient control;

    public OllamaTranslator(TranslatorProperties properties) {
        this.properties = properties;
        this.client = Ollama.client(properties.url(), properties.timeout());
        this.control = Ollama.client(properties.url(), Ollama.CONTROL_TIMEOUT);
    }

    @Override
    public Map<String, String> translate(Map<String, String> texts) {
        if (client == null) {
            return Map.of();
        }
        Map<String, String> translations = new LinkedHashMap<>();
        texts.forEach((name, text) -> translations.put(name, translate(text)));
        return translations;
    }

    @Override
    public void unload() {
        if (control == null) {
            return;
        }
        try {
            Ollama.unload(control, properties.model());
        } catch (RestClientException e) {
            log.warn("Could not unload the translation model: {}", e.getMessage());
        }
    }

    private String translate(String text) {
        Map<String, Object> request = Map.of(
                "model", properties.model(),
                "stream", false,
                "think", false,
                // Stays loaded until unload()
                "keep_alive", -1,
                "options", Map.of("temperature", 0.2),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM),
                        Map.of("role", "user", "content", text)));
        JsonNode answer;
        try {
            answer = client.post().uri("/api/chat").body(request).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Translation failed: {}", e.getMessage());
            throw new JobFailedException("prompt translation failed");
        }
        String english = clean(answer != null ? answer.path("message").path("content").asString("") : "");
        if (english.isEmpty()) {
            throw new JobFailedException("prompt translation failed");
        }
        return english;
    }

    /** The model is told to answer the bare text, but may still wrap it in quotes */
    static String clean(String answer) {
        String text = answer.strip();
        if (text.length() > 1 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("«") && text.endsWith("»"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }
}
