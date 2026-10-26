package ee.gaile.imagegen.web;

import ee.gaile.imagegen.gpu.GpuState;
import ee.gaile.imagegen.gpu.UnloadPolicy;
import ee.gaile.imagegen.job.ComfyGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The page's presence and the model unload (docs/CONTRACT.md, "GPU state and unload").
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GpuController {

    private final UnloadPolicy policy;
    private final ComfyGateway gateway;

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
