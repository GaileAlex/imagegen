package ee.gaile.imagegen.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A job runs until the test finishes it with {@link #complete} or {@link #fail}, or it is interrupted.
 */
public class FakeGateway implements ComfyGateway {

    public final List<String> started = new CopyOnWriteArrayList<>();
    public final LinkedBlockingQueue<Job> runningJobs = new LinkedBlockingQueue<>();
    public volatile int frees;
    public volatile int interrupts;
    private volatile Job current;

    private final Map<Job, LinkedBlockingQueue<Object>> outcomes = new ConcurrentHashMap<>();

    @Override
    public JobResult run(Job job, Consumer<Progress> progress, Instant deadline) {
        LinkedBlockingQueue<Object> outcome = outcomes.computeIfAbsent(job, j -> new LinkedBlockingQueue<>());
        started.add(job.getModel());
        current = job;
        runningJobs.add(job);
        try {
            Object value = outcome.poll(10, TimeUnit.SECONDS);
            if (value instanceof JobResult result) {
                return result;
            }
            throw new JobFailedException(value == null ? "test timeout" : value.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailedException("interrupted");
        }
    }

    public Job awaitRunning() throws InterruptedException {
        Job job = runningJobs.poll(5, TimeUnit.SECONDS);
        if (job == null) {
            throw new AssertionError("no job started");
        }
        return job;
    }

    public void complete(Job job) {
        outcomes.computeIfAbsent(job, j -> new LinkedBlockingQueue<>())
                .add(new JobResult("image/png", 10, 64, 64, null));
    }

    public void fail(Job job, String message) {
        outcomes.computeIfAbsent(job, j -> new LinkedBlockingQueue<>()).add(message);
    }

    @Override
    public void interrupt() {
        interrupts++;
        if (current != null) {
            fail(current, "interrupted");
        }
    }

    @Override
    public void free() {
        frees++;
    }

    @Override
    public boolean isUp() {
        return true;
    }
}
