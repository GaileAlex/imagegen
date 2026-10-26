package ee.gaile.imagegen.gpu;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of GET /api/state without comfyuiUp, which the controller adds.
 *
 * @param idleUnloadAt when the idle timer unloads the model, null while a job is pending or nothing is loaded
 */
public record GpuState(boolean loaded, String lastModel, int queueLength, UUID runningJobId, boolean unloadPending,
                       Instant idleUnloadAt, int presentUsers) {
}
