package ee.gaile.imagegen.web;

import ee.gaile.imagegen.gpu.GpuState;
import ee.gaile.imagegen.gpu.SpeechHold;
import ee.gaile.imagegen.gpu.UnloadPolicy;
import ee.gaile.imagegen.job.ComfyGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The page's presence, the model unload and the holds of the GPU for the speech of the chat (docs/CONTRACT.md,
 * "GPU state and unload", "GPU hold for the speech").
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GpuController {

    private final UnloadPolicy policy;
    private final ComfyGateway gateway;
    private final SpeechHold speechHold;

    @PostMapping("/presence/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestHeader("X-User-Id") long userId) {
        policy.heartbeat(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/presence/leave")
    public ResponseEntity<Void> leave(@RequestHeader("X-User-Id") long userId) {
        policy.leave(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unload")
    public ResponseEntity<Map<String, String>> unload() {
        // Whatever is still loaded after the request waits for the queue, so it is pending
        String state = policy.requestUnload() == UnloadPolicy.UnloadState.UNLOADED ? "UNLOADED" : "PENDING";
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("state", state));
    }

    /**
     * Called by cv-app around every recognition and synthesis of the chat and by Whisper before it loads a model;
     * answers at once, the running prompt waits within a fraction of a second.
     */
    @PostMapping("/gpu/hold")
    public SpeechHold.Held hold(@RequestBody HoldRequest request) {
        return speechHold.hold(Duration.ofMillis(Math.round(request.seconds() * 1000)),
                request.roomMb() == null ? 0 : request.roomMb());
    }

    /**
     * @param seconds how long from now, at most 120
     * @param roomMb  the VRAM to make free, null or 0 = none
     */
    public record HoldRequest(double seconds, Long roomMb) {
    }

    @GetMapping("/state")
    public StateView state() {
        GpuState state = policy.state();
        return new StateView(gateway.isUp(), state.loaded(), state.lastModel(), state.queueLength(),
                state.runningJobId(), state.unloadPending(), state.idleUnloadAt(), state.presentUsers());
    }

    /**
     * GET /api/state: {@link GpuState} plus whether ComfyUI answers.
     */
    public record StateView(boolean comfyuiUp, boolean loaded, String lastModel, int queueLength, UUID runningJobId,
                            boolean unloadPending, Instant idleUnloadAt, int presentUsers) {
    }
}
