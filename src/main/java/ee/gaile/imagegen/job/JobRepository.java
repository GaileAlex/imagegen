package ee.gaile.imagegen.job;

import org.slf4j.LoggerFactory;

/**
 * Keeps the job record (a json file next to the result, see docs/CONTRACT.md "Storage"). The queue saves a job on
 * every status change, not on progress.
 */
public interface JobRepository {

    void save(Job job);

    /**
     * For a job that goes on in memory whatever its record says: a record that cannot be written (a full disk) must
     * not end the single worker thread nor fail a job that runs.
     */
    default void saveQuietly(Job job) {
        try {
            save(job);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(JobRepository.class).error("Job {} could not be saved", job.getId(), e);
        }
    }
}
