package ee.gaile.imagegen.job;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One generation. The status fields are changed by the worker thread of {@link JobQueue} and read by the request
 * threads, hence volatile.
 */
@Getter
@Setter
public class Job {

    private final UUID id;
    private final long userId;
    private final String userName;
    /** Id of the mode in the catalogue, e.g. muse-txt2img */
    private final String model;
    private final JobKind kind;
    /** Client params after validation, with the seed and the derived sizes filled in */
    private final Map<String, Object> params;
    private final long seed;
    private final Instant createdAt;

    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String error;
    private volatile JobResult result;
    /** Param name -> the English text that went to ComfyUI instead of the Russian one; empty when none was needed */
    private volatile Map<String, String> translations = Map.of();
    /** An image job that had not finished at the submit: its picture becomes the input when this job starts */
    private volatile UUID sourceJobId;

    /** Not stored: only meaningful while RUNNING */
    private volatile Progress progress;
    /** Set by a cancel of the running job, before ComfyUI is interrupted */
    private volatile boolean cancelRequested;

    public Job(UUID id, long userId, String userName, String model, JobKind kind, Map<String, Object> params,
               long seed, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.userName = userName;
        this.model = model;
        this.kind = kind;
        this.params = Map.copyOf(params);
        this.seed = seed;
        this.createdAt = createdAt;
    }

}
