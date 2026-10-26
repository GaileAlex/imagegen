package ee.gaile.imagegen.job;

/**
 * Keeps the job record (a json file next to the result, see docs/CONTRACT.md "Storage"). The queue saves a job on
 * every status change, not on progress.
 */
public interface JobRepository {

    void save(Job job);
}
