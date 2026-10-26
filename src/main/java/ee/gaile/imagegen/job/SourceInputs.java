package ee.gaile.imagegen.job;

/**
 * The input of a job that was submitted with an image job still pending as its source (a video from a picture made
 * for it): the picture is only there once the source has finished, which the queue order guarantees.
 */
public interface SourceInputs {

    /**
     * Writes the result of the source job as the input image of the job, right before it runs.
     *
     * @throws JobFailedException when the source failed, was cancelled or deleted
     */
    void prepare(Job job);
}
