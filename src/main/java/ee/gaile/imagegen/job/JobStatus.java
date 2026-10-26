package ee.gaile.imagegen.job;

public enum JobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    public boolean isFinished() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
