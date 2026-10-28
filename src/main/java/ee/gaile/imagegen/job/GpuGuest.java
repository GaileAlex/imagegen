package ee.gaile.imagegen.job;

/**
 * A model that borrows the GPU between the jobs (the prompt ideas, see {@link JobQueue#lendGpu}). It must leave
 * before ComfyUI loads a model again: both do not fit into the 16 GB.
 */
public interface GpuGuest {

    /** Frees the GPU memory of the guest; nothing happens when it is not loaded */
    void leaveGpu();
}
