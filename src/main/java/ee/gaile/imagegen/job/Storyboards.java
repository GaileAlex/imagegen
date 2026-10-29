package ee.gaile.imagegen.job;

import java.util.List;

/**
 * Writes the prompts of the scenes of a long video whose user gave a story or a short idea instead of the scenes.
 * The job holds the GPU meanwhile: ComfyUI has unloaded its model, and the queue sends the language model away
 * ({@link GpuGuest#leaveGpu}) before the first scene.
 */
public interface Storyboards {

    /**
     * @return the scenes of the job with their prompts, in English; the lengths as they were
     * @throws JobFailedException the language model is not set, does not answer or answers nonsense
     */
    List<Scene> write(Job job);
}
