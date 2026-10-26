package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.config.ImagegenProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The one websocket of the app to ComfyUI (ws://.../ws?clientId=...), kept open: when it closes, a new one is opened
 * a few seconds later. While it is down the gateway falls back to polling /history, so a restart of ComfyUI costs
 * the progress bar, not the job.
 */
@Slf4j
@Component
public class ComfyWebSocket implements ComfyEvents {

    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(3);

    private final String clientId = UUID.randomUUID().toString();
    private final URI uri;
    private final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<Consumer<ComfyEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean connected;
    private volatile WebSocket socket;
    private Thread thread;

    public ComfyWebSocket(ImagegenProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.uri = URI.create(properties.comfyuiUrl().replaceFirst("^http", "ws").replaceAll("/+$", "")
                + "/ws?clientId=" + clientId);
    }

    @PostConstruct
    public void start() {
        thread = Thread.ofPlatform().name("comfy-websocket").daemon().start(this::keepConnected);
    }

    @PreDestroy
    public void stop() {
        thread.interrupt();
        WebSocket open = socket;
        if (open != null) {
            open.abort();
        }
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public Runnable subscribe(Consumer<ComfyEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void keepConnected() {
        boolean reportedDown = false;
        while (!Thread.currentThread().isInterrupted()) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            try {
                socket = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(uri, new Listener(closed)).join();
                log.info("Connected to the ComfyUI websocket");
                reportedDown = false;
                closed.join();
                log.warn("The ComfyUI websocket closed, reconnecting");
            } catch (RuntimeException e) {
                // The first failure is worth a warning, the retries every 3 s while ComfyUI is down are not
                if (!reportedDown) {
                    log.warn("No ComfyUI websocket: {}", e.getMessage());
                    reportedDown = true;
                }
            } finally {
                connected = false;
            }
            try {
                Thread.sleep(RECONNECT_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void dispatch(String text) {
        try {
            JsonNode message = mapper.readTree(text);
            ComfyEvent event = new ComfyEvent(message.path("type").asString(), message.path("data"));
            listeners.forEach(listener -> listener.accept(event));
        } catch (RuntimeException e) {
            log.debug("Ignored a websocket message: {}", e.getMessage());
        }
    }

    private class Listener implements WebSocket.Listener {

        private final CompletableFuture<Void> closed;
        private final StringBuilder text = new StringBuilder();

        Listener(CompletableFuture<Void> closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                dispatch(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            // Previews of the sampler: not used
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("ComfyUI websocket error: {}", error.toString());
            closed.complete(null);
        }
    }
}
