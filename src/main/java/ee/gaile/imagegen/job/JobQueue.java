package ee.gaile.imagegen.job;

import ee.gaile.imagegen.config.ImagegenProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One job at a time on the GPU, in the order of submission. A single worker thread takes the next job and runs it
 * through ComfyUI; the unload of the model happens under the same lock, so it never cuts a job off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueue {

    private final ComfyGateway gateway;
    private final PromptTranslator translator;
    private final JobRepository repository;
    private final ImagegenProperties properties;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition jobQueued = lock.newCondition();
    private final Deque<Job> queued = new ArrayDeque<>();
    private Job running;
    /** A job ran since the last unload, so ComfyUI holds a model */
    private boolean loaded;
    private String lastModel;
    /** End of the last job */
    private Instant lastActivity;
    private Thread worker;

    @PostConstruct
    public void start() {
        worker = Thread.ofPlatform().name("imagegen-worker").daemon().start(this::work);
    }

    @PreDestroy
    public void stop() {
        worker.interrupt();
    }

    /**
     * @return the jobs ahead of this one, the running one included (0 = it starts now)
     * @throws TooManyJobsException when the user or the whole queue has too many jobs pending
     */
    public int submit(Job job) {
        lock.lock();
        try {
            int pending = queued.size() + (running != null ? 1 : 0);
            if (pending >= properties.maxQueue()) {
                throw new TooManyJobsException("The queue is full: " + pending + " jobs");
            }
            if (pendingOf(job.getUserId()) >= properties.maxJobsPerUser()) {
                throw new TooManyJobsException("At most " + properties.maxJobsPerUser() + " jobs at a time");
            }
            repository.save(job);
            queued.addLast(job);
            jobQueued.signal();
            log.info("Job {} queued: {} of user {}, {} ahead", job.getId(), job.getModel(), job.getUserId(), pending);
            return pending;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the jobs ahead of this one (0 = running), -1 when it is not pending
     */
    public int position(Job job) {
        lock.lock();
        try {
            if (job == running) {
                return 0;
            }
            int index = 0;
            for (Job other : queued) {
                if (other == job) {
                    return index + (running != null ? 1 : 0);
                }
                index++;
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * A queued job leaves the queue; the running one is interrupted in ComfyUI and ends as CANCELLED.
     *
     * @return false when the job is not pending (finished already)
     */
    public boolean cancel(Job job) {
        lock.lock();
        try {
            if (queued.remove(job)) {
                job.setStatus(JobStatus.CANCELLED);
                job.setFinishedAt(clock.instant());
                repository.save(job);
                log.info("Job {} cancelled in the queue", job.getId());
                return true;
            }
            if (job == running) {
                job.setCancelRequested(true);
                // Under the lock: the worker cannot start the next job meanwhile, so the interrupt hits this one
                gateway.interrupt();
                log.info("Job {} cancelled while running", job.getId());
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Unloads the model unless a job is running or queued.
     *
     * @return true when nothing is loaded any more
     */
    public boolean unloadIfIdle() {
        lock.lock();
        try {
            if (!loaded) {
                return true;
            }
            if (running != null || !queued.isEmpty()) {
                return false;
            }
            try {
                gateway.free();
            } catch (RuntimeException e) {
                log.warn("Unload failed: {}", e.getMessage());
                return false;
            }
            loaded = false;
            translator.unload();
            log.info("Model unloaded, {} after the last job", Duration.between(lastActivity, clock.instant()));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public QueueState state() {
        lock.lock();
        try {
            return new QueueState(loaded, lastModel, queued.size() + (running != null ? 1 : 0),
                    running != null ? running.getId() : null, lastActivity);
        } finally {
            lock.unlock();
        }
    }

    private int pendingOf(long userId) {
        int count = running != null && running.getUserId() == userId ? 1 : 0;
        for (Job job : queued) {
            if (job.getUserId() == userId) {
                count++;
            }
        }
        return count;
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            lock.lock();
            try {
                while (queued.isEmpty()) {
                    jobQueued.await();
                }
                job = queued.pollFirst();
                running = job;
                loaded = true;
                lastModel = job.getModel();
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(clock.instant());
                job.setProgress(Progress.LOADING);
                repository.save(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            execute(job);
        }
    }

    private void execute(Job job) {
        Instant deadline = clock.instant().plus(properties.jobTimeout());
        JobResult result = null;
        String error = null;
        try {
            translate(job);
            // A cancel during the translation: ComfyUI never gets the job
            if (!job.isCancelRequested()) {
                result = gateway.run(job, job::setProgress, deadline);
            }
        } catch (JobFailedException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.error("Job {} failed unexpectedly", job.getId(), e);
            error = "internal error";
        }

        lock.lock();
        try {
            if (job.isCancelRequested()) {
                job.setStatus(JobStatus.CANCELLED);
            } else if (result != null) {
                job.setResult(result);
                job.setStatus(JobStatus.DONE);
            } else {
                job.setError(error);
                job.setStatus(JobStatus.FAILED);
            }
            job.setFinishedAt(clock.instant());
            job.setProgress(null);
            running = null;
            lastActivity = job.getFinishedAt();
            repository.save(job);
        } catch (RuntimeException e) {
            log.error("Job {} could not be saved", job.getId(), e);
        } finally {
            lock.unlock();
        }
        log.info("Job {} {} in {}{}", job.getId(), job.getStatus(),
                Duration.between(job.getStartedAt(), job.getFinishedAt()), error != null ? ": " + error : "");
    }

    /** On the CPU, so the GPU model of the previous job may stay loaded meanwhile */
    private void translate(Job job) {
        Map<String, String> texts = PromptTranslator.cyrillicTexts(job.getParams());
        if (texts.isEmpty()) {
            return;
        }
        job.setProgress(Progress.TRANSLATING);
        job.setTranslations(Map.copyOf(translator.translate(texts)));
        job.setProgress(Progress.LOADING);
        repository.save(job);
        log.info("Job {}: translated {}", job.getId(), job.getTranslations().keySet());
    }

    /**
     * @param pending      queued and running jobs
     * @param lastActivity end of the last job, null before the first one
     */
    public record QueueState(boolean loaded, String lastModel, int pending, UUID runningJobId, Instant lastActivity) {

        public boolean idle() {
            return pending == 0;
        }
    }
}
