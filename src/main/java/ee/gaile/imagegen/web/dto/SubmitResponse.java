package ee.gaile.imagegen.web.dto;

import ee.gaile.imagegen.job.JobStatus;

import java.util.UUID;

/**
 * Answer 202 of POST /api/jobs.
 *
 * @param position jobs ahead of the new one, the running one included
 */
public record SubmitResponse(UUID jobId, JobStatus status, int position, long seed) {
}
