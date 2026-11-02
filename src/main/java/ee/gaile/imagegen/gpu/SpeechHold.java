package ee.gaile.imagegen.gpu;

import ee.gaile.imagegen.job.ComfyGateway;
import ee.gaile.imagegen.job.GpuHolds;
import ee.gaile.imagegen.job.JobQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The chat before the pictures: the speech services (Whisper, the TTS, the local chat model) share the GPU with
 * ComfyUI and slow down 2-7 times next to a video job. cv-app holds the GPU while the speech goes on (every
 * recognition and synthesis renews the hold), Whisper asks for room before it loads a model. A running prompt waits
 * meanwhile and goes on from the same place (comfyui-service/gpu_yield); its deadlines move by the holds
 * ({@link GpuHolds}). Nothing is cancelled and the queue keeps taking jobs: a new one waits in ComfyUI as well.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeechHold {

    /** A longer ask is cut: the callers renew the hold while the speech goes on */
    static final Duration MAX = Duration.ofMinutes(2);

    private final GpuHolds holds;
    private final ComfyGateway gateway;
    private final JobQueue queue;

    /**
     * @param roomMb the VRAM the caller needs free, 0 = none
     * @return what was held
     */
    public Held hold(Duration requested, long roomMb) {
        Duration duration = requested.isNegative() ? Duration.ZERO : requested.compareTo(MAX) > 0 ? MAX : requested;
        boolean started = holds.extend(duration);
        long freeMb = -1;
        try {
            freeMb = gateway.hold(duration, Math.max(roomMb, 0));
        } catch (RuntimeException e) {
            // the speech goes on as without the hold
            log.warn("ComfyUI did not take the hold for the speech: {}", e.getMessage());
        }
        if (started) {
            log.info("GPU held for the speech: {} s{}", duration.toSeconds(),
                    roomMb > 0 ? ", room " + roomMb + " MB" : "");
        }
        // A running prompt gives the room back itself; with none, the model of the last job (or the prompt idea
        // model) sits in the VRAM and is unloaded: the next job loads it again
        JobQueue.QueueState state = queue.state();
        if (roomMb > 0 && freeMb >= 0 && freeMb < roomMb && state.idle()) {
            boolean unloaded = queue.unloadIfIdle();
            log.info("Room for the speech: {} MB free of {} MB asked, {}", freeMb, roomMb, !unloaded
                    ? "a job started meanwhile"
                    : state.loaded() ? "the model of the last job unloaded" : "nothing of imagegen was loaded");
        }
        return new Held(duration.toMillis() / 1000.0, freeMb);
    }

    /**
     * @param seconds the hold asked, cut to {@link #MAX}
     * @param freeMb  the VRAM free on the card in MB when ComfyUI took the hold, -1 unknown
     */
    public record Held(double seconds, long freeMb) {
    }
}
