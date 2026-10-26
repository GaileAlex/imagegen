package ee.gaile.imagegen.job;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * What the queue needs from ComfyUI. Only the worker thread of {@link JobQueue} calls {@link #run}; ComfyUI runs one
 * prompt at a time anyway.
 */
public interface ComfyGateway {

    /**
     * Builds the workflow of the job (template + params + its input files), runs it, waits for the end and stores
     * the output file next to the job record.
     *
     * @param progress called from the websocket listener while the job runs
     * @param deadline the job fails with "timeout" after it (ComfyUI is interrupted)
     * @throws JobFailedException on an execution error of ComfyUI, a timeout or a lost connection; also after
     *                            {@link #interrupt()}, the queue tells the two apart by {@link Job#isCancelRequested()}
     */
    JobResult run(Job job, Consumer<Progress> progress, Instant deadline);

    /** Stops the prompt ComfyUI is running now (POST /interrupt). */
    void interrupt();

    /** Unloads every model from VRAM and frees cached memory (POST /free). */
    void free();

    /** ComfyUI answers /system_stats */
    boolean isUp();
}
