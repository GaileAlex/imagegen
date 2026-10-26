package ee.gaile.imagegen.service;

import ee.gaile.imagegen.image.InputImages;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.SourceInputs;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.storage.JobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;

/**
 * The pictures of earlier jobs taken as the input of a new one (sourceJobId): a finished picture is read at the
 * submit; a picture still in the queue is written as the input of the video right before the video runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceImages implements SourceInputs {

    private final JobStore store;
    private final JobFiles files;

    /**
     * @return the source job of the user that will give a picture: an image, edit or inpaint job, finished with an
     * image or not finished yet
     * @throws InvalidParamsException for a job of another user, a video, or a job that ended without a picture
     */
    public Job find(long userId, UUID sourceJobId) {
        Optional<Job> source = store.find(sourceJobId)
                .filter(job -> job.getUserId() == userId)
                .filter(job -> job.getKind() != JobKind.VIDEO)
                .filter(job -> !job.getStatus().isFinished() || hasPicture(job));
        return source.orElseThrow(() -> new InvalidParamsException("sourceJobId is not an image job of yours"));
    }

    /** The result file of a finished image job */
    public byte[] read(Job source) {
        try {
            return Files.readAllBytes(files.result(source.getId(), source.getResult().contentType()));
        } catch (IOException e) {
            throw new InvalidParamsException("The image of sourceJobId is gone");
        }
    }

    @Override
    public void prepare(Job job) {
        Job source = store.find(job.getSourceJobId())
                .orElseThrow(() -> new JobFailedException("the start image job is gone"));
        if (!hasPicture(source)) {
            throw new JobFailedException("the start image job ended as " + source.getStatus());
        }
        try {
            InputImages.writePng(InputImages.limitSide(InputImages.decode(read(source)), InputImages.MAX_VIDEO_SIDE),
                    files.input(job.getId()));
        } catch (InvalidParamsException | UncheckedIOException e) {
            throw new JobFailedException("the start image cannot be used: " + e.getMessage(), e);
        }
        log.info("Job {}: the picture of job {} is its start image", job.getId(), source.getId());
    }

    private static boolean hasPicture(Job job) {
        return job.getStatus() == JobStatus.DONE && job.getResult() != null
                && job.getResult().contentType().startsWith("image/");
    }
}
