package ee.gaile.imagegen.gpu;

import ee.gaile.imagegen.config.ImagegenProperties;
import ee.gaile.imagegen.job.JobQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * When the model leaves VRAM (it takes ~13 GB of the 16 GB shared with Ollama, Whisper and the TTS services):
 * 30 min after the last job; at once on the unload button; when the last user with the page open leaves it (or its
 * heartbeats stop). A running or queued job is never cut off: the unload then waits for the queue to drain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnloadPolicy {

    private final JobQueue queue;
    private final ImagegenProperties properties;
    private final Clock clock;

    /** User id -> last heartbeat of the page */
    private final Map<Long, Instant> presence = new HashMap<>();
    private boolean unloadPending;

    public synchronized void heartbeat(long userId) {
        presence.put(userId, clock.instant());
    }

    public synchronized void leave(long userId) {
        presence.remove(userId);
        if (presence.isEmpty()) {
            unloadPending = true;
        }
        evaluate();
    }

    /** Before a job of this user is queued: whoever generates is on the page, and the model will be needed */
    public synchronized void beforeSubmit(long userId) {
        presence.put(userId, clock.instant());
        unloadPending = false;
    }

    /**
     * @return UNLOADED or PENDING (waits for the queue to drain)
     */
    public synchronized UnloadState requestUnload() {
        unloadPending = true;
        return evaluate();
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void tick() {
        evaluate();
    }

    public synchronized GpuState state() {
        JobQueue.QueueState queueState = queue.state();
        Instant idleUnloadAt = queueState.loaded() && queueState.idle() && queueState.lastActivity() != null
                ? queueState.lastActivity().plus(properties.idleUnload())
                : null;
        return new GpuState(queueState.loaded(), queueState.lastModel(), queueState.pending(),
                queueState.runningJobId(), unloadPending, idleUnloadAt, presence.size());
    }

    private UnloadState evaluate() {
        Instant now = clock.instant();
        boolean expired = presence.values().removeIf(seen -> seen.plus(properties.presenceTimeout()).isBefore(now));
        if (expired && presence.isEmpty()) {
            unloadPending = true;
        }

        JobQueue.QueueState queueState = queue.state();
        if (!queueState.loaded()) {
            unloadPending = false;
            return UnloadState.UNLOADED;
        }
        boolean idleTooLong = queueState.idle() && queueState.lastActivity() != null
                && !now.isBefore(queueState.lastActivity().plus(properties.idleUnload()));
        if ((unloadPending || idleTooLong) && queue.unloadIfIdle()) {
            log.info("Unloaded: {}", unloadPending ? "requested or nobody on the page" : "idle");
            unloadPending = false;
            return UnloadState.UNLOADED;
        }
        return unloadPending ? UnloadState.PENDING : UnloadState.LOADED;
    }

    public enum UnloadState {
        LOADED,
        PENDING,
        UNLOADED
    }
}
