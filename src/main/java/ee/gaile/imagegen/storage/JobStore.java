package ee.gaile.imagegen.storage;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the REST layer needs on top of {@link JobRepository}, which the queue sees. A separate interface keeps the
 * queue's one-method repository as it is.
 */
public interface JobStore extends JobRepository {

    Optional<Job> find(UUID id);

    /**
     * @return jobs of the user, newest first
     */
    List<Job> findByUser(long userId, int limit);

    /** The record and all the files of the job */
    void delete(UUID id);
}
