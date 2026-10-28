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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One job at a time on the GPU. The users take turns: the next job is the oldest one of the user served longest ago
 * (a user never served comes first), so a long row of jobs of one user does not hold the others up; the jobs of one
 * user keep their order (a picture runs before the video waiting for it). A single worker thread takes the next job
 * and runs it through ComfyUI; the unload of the model happens under the same lock, so it never cuts a job off.
 * Between the jobs a {@link GpuGuest} may borrow the GPU: the next job waits until it is done and has left.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueue {

    private final ComfyGateway gateway;
    private final PromptTranslator translator;
    private final SourceInputs sourceInputs;
    private final JobRepository repository;
    private final ImagegenProperties properties;
    private final Clock clock;
    private final GpuGuest guest;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition jobQueued = lock.newCondition();
    private final Deque<Job> queued = new ArrayDeque<>();
    private Job running;
    /** The turn number of the last job taken of each user; the turns count up with every job taken */
    private final Map<Long, Long> lastTurns = new HashMap<>();
    private long turn;
    /** A job ran since the last unload, so ComfyUI holds a model */
    private boolean loaded;
    private String lastModel;
    /** End of the last job */
    private Instant lastActivity;
    private Thread worker;
    /** Guests on the GPU right now; no job starts while there are any */
    private int guests;
    /** A guest used the GPU since it last left: its model may still be there */
    private boolean guestLoaded;

    @PostConstruct
    public void start() {
        worker = Thread.ofPlatform().name("imagegen-worker").daemon().start(this::work);
    }

    @PreDestroy
    public void stop() {
        worker.interrupt();
    }

    /**
     * No limit on the jobs: the users take turns, so a long row of one user does not hold the others up.
     *
     * @return the jobs ahead of this one, the running one included (0 = it starts now)
     */
    public int submit(Job job) {
        lock.lock();
        try {
            int pending = queued.size() + (running != null ? 1 : 0);
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
            int index = plannedOrder().indexOf(job);
            return index < 0 ? -1 : index + (running != null ? 1 : 0);
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
     * Lends the GPU to a guest when no job is running or waiting; the model of the last job is unloaded first (the
     * next job would mostly load its model anyway). Every true answer must be followed by {@link #returnGpu}.
     *
     * @return false: a job has the GPU or is about to, the guest runs elsewhere (CPU)
     */
    public boolean lendGpu() {
        lock.lock();
        try {
            if (running != null || !queued.isEmpty()) {
                return false;
            }
            if (loaded) {
                try {
                    gateway.free();
                } catch (RuntimeException e) {
                    log.warn("Unload for a GPU guest failed: {}", e.getMessage());
                    return false;
                }
                loaded = false;
                log.info("Model unloaded for a GPU guest");
            }
            guests++;
            guestLoaded = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The guest is done; a waiting job may start (the guest leaves the GPU before it) */
    public void returnGpu() {
        lock.lock();
        try {
            guests--;
            jobQueued.signal();
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
            if (guests == 0 && guestLoaded) {
                guest.leaveGpu();
                guestLoaded = false;
            }
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

    /**
     * The queued jobs in the order the worker will take them, if nothing is added or cancelled meanwhile. Users with
     * the same last turn (never served) go in the order of their first queued job.
     */
    private List<Job> plannedOrder() {
        Map<Long, Deque<Job>> byUser = new LinkedHashMap<>();
        for (Job job : queued) {
            byUser.computeIfAbsent(job.getUserId(), id -> new ArrayDeque<>()).addLast(job);
        }
        Map<Long, Long> turns = new HashMap<>(lastTurns);
        long next = turn;
        List<Job> order = new ArrayList<>(queued.size());
        while (!byUser.isEmpty()) {
            Long user = null;
            for (Long candidate : byUser.keySet()) {
                if (user == null || turns.getOrDefault(candidate, -1L) < turns.getOrDefault(user, -1L)) {
                    user = candidate;
                }
            }
            Deque<Job> jobs = byUser.get(user);
            order.add(jobs.pollFirst());
            if (jobs.isEmpty()) {
                byUser.remove(user);
            }
            turns.put(user, ++next);
        }
        return order;
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            boolean guestLeaves;
            lock.lock();
            try {
                while (queued.isEmpty() || guests > 0) {
                    jobQueued.await();
                }
                guestLeaves = guestLoaded;
                guestLoaded = false;
                job = plannedOrder().getFirst();
                queued.remove(job);
                lastTurns.put(job.getUserId(), ++turn);
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
            if (guestLeaves) {
                guest.leaveGpu();
            }
            execute(job);
        }
    }

    private void execute(Job job) {
        Instant deadline = clock.instant().plus(properties.jobTimeout());
        JobResult result = null;
        String error = null;
        try {
            if (job.getSourceJobId() != null) {
                sourceInputs.prepare(job);
            }
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
