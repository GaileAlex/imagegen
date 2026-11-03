package ee.gaile.imagegen.ollama;

import ee.gaile.imagegen.config.RestClients;
import lombok.experimental.UtilityClass;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/** What the translation and the idea model share of the API of Ollama */
@UtilityClass
public class Ollama {

    /**
     * The control calls (what is loaded, an unload) answer in a moment; some are made under the lock of the queue,
     * so a stuck instance must not hold it for the minutes an answer of a model may take
     */
    public static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** null for an empty url: that instance is not used */
    public static RestClient client(String url, Duration readTimeout) {
        return url == null || url.isBlank() ? null : RestClients.of(url, CONNECT_TIMEOUT, readTimeout);
    }

    /**
     * Sends the model out of the memory of the instance.
     *
     * @throws RestClientException the instance does not answer
     */
    public static void unload(RestClient client, String model) {
        client.post().uri("/api/generate")
                .body(Map.of("model", model, "keep_alive", 0))
                .retrieve()
                .toBodilessEntity();
    }
}
