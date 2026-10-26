package ee.gaile.imagegen.web.dto;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.Progress;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A job as GET /api/jobs shows it.
 *
 * @param position jobs ahead of it including the running one, null once it is not pending
 * @param expiresAt    finishedAt + retention, null while it is pending
 * @param translations param name -> the English text the model got instead of the Russian one
 */
public record JobView(UUID jobId, String model, JobKind kind, JobStatus status, Integer position, Progress progress,
                      Map<String, Object> params, Map<String, String> translations, long seed, Instant createdAt,
                      Instant startedAt, Instant finishedAt, Instant expiresAt, String error, JobResult result) {

    public static JobView of(Job job, int position, Duration retention) {
        Instant finishedAt = job.getFinishedAt();
        return new JobView(job.getId(), job.getModel(), job.getKind(), job.getStatus(),
                position >= 0 ? position : null, job.getProgress(), job.getParams(), job.getTranslations(),
                job.getSeed(), job.getCreatedAt(), job.getStartedAt(), finishedAt,
                finishedAt != null ? finishedAt.plus(retention) : null, job.getError(), job.getResult());
    }
}
