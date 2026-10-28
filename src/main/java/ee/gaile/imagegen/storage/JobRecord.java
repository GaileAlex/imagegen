package ee.gaile.imagegen.storage;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The content of {dataDir}/jobs/&lt;jobId&gt;.json: a {@link Job} without what only matters while it runs.
 */
record JobRecord(UUID jobId, long userId, String userName, String model, JobKind kind, JobStatus status,
                 Map<String, Object> params, long seed, Instant createdAt, Instant startedAt, Instant finishedAt,
                 String error, JobResult result, Map<String, String> translations, UUID sourceJobId,
                 Boolean pinned, Instant unpinnedAt) {

    static JobRecord of(Job job) {
        return new JobRecord(job.getId(), job.getUserId(), job.getUserName(), job.getModel(), job.getKind(),
                job.getStatus(), job.getParams(), job.getSeed(), job.getCreatedAt(), job.getStartedAt(),
                job.getFinishedAt(), job.getError(), job.getResult(), job.getTranslations(), job.getSourceJobId(),
                job.isPinned(), job.getUnpinnedAt());
    }

    Job toJob() {
        Job job = new Job(jobId, userId, userName, model, kind, params, seed, createdAt);
        job.setStatus(status);
        job.setStartedAt(startedAt);
        job.setFinishedAt(finishedAt);
        job.setError(error);
        job.setResult(result);
        // Records written before the translation have none
        job.setTranslations(translations != null ? translations : Map.of());
        job.setSourceJobId(sourceJobId);
        // Records written before the pin have none
        job.setPinned(Boolean.TRUE.equals(pinned));
        job.setUnpinnedAt(unpinnedAt);
        return job;
    }
}
