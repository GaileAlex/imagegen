package ee.gaile.imagegen.job;

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
     * @param deadline the job fails when it is past or ComfyUI stays silent too long (ComfyUI is interrupted)
     * @throws JobFailedException on an execution error of ComfyUI, a timeout or a lost connection; also after
     *                            {@link #interrupt()}, the queue tells the two apart by {@link Job#isCancelRequested()}
     */
    JobResult run(Job job, Consumer<Progress> progress, Deadline deadline);

    /**
     * Makes one scene of a long video ({@link Job#getScenes()}): the first from the start image of the job (or the
     * text alone), every next one from the last frames of the scene before. The scene and its last frames are kept
     * next to the job record until {@link #joinScenes}.
     *
     * @param scene 1-based
     * @throws JobFailedException as {@link #run}
     */
    void runScene(Job job, int scene, Consumer<Progress> progress, Deadline deadline);

    /**
     * Joins all the scenes of a long video into its result: each scene after the first without the frames it
     * started from, which the scene before already shows. The scene files are deleted afterwards.
     *
     * @throws JobFailedException as {@link #run}
     */
    JobResult joinScenes(Job job, Consumer<Progress> progress, Deadline deadline);

    /** Deletes the scene files of a long video that will not be joined (it failed or was cancelled) */
    void discardScenes(Job job);

    /** Stops the prompt ComfyUI is running now (POST /interrupt). */
    void interrupt();

    /** Unloads every model from VRAM and frees cached memory (POST /free). */
    void free();

    /** ComfyUI answers /system_stats */
    boolean isUp();
}
