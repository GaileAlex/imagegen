package ee.gaile.imagegen.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.job.Progress;
import ee.gaile.imagegen.job.Scene;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A job as GET /api/jobs shows it.
 *
 * @param position jobs ahead of it including the running one, null once it is not pending
 * @param expiresAt    finishedAt (or the last unpin) + retention, null while it is pending or pinned
 * @param translations param name -> the English text the model got instead of the Russian one
 * @param pinned       kept past the retention, not deleted until unpinned
 * @param scenes       a long video: its scenes (prompts null until its storyboard is written); absent otherwise
 */
public record JobView(UUID jobId, String model, JobKind kind, JobStatus status, Integer position, Progress progress,
                      Map<String, Object> params, Map<String, String> translations, long seed, Instant createdAt,
                      Instant startedAt, Instant finishedAt, Instant expiresAt, String error, JobResult result,
                      boolean pinned, @JsonInclude(JsonInclude.Include.NON_NULL) List<Scene> scenes) {

    public static JobView of(Job job, int position, Duration retention) {
        Instant finishedAt = job.getFinishedAt();
        Instant keptSince = job.keptSince();
        return new JobView(job.getId(), job.getModel(), job.getKind(), job.getStatus(),
                position >= 0 ? position : null, job.getProgress(), job.getParams(), job.getTranslations(),
                job.getSeed(), job.getCreatedAt(), job.getStartedAt(), finishedAt,
                keptSince != null && !job.isPinned() ? keptSince.plus(retention) : null, job.getError(),
                job.getResult(), job.isPinned(), job.getScenes());
    }
}
