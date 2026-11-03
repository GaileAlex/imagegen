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
 * A long video runs one scene per turn and goes back into the queue in between (RUNNING, phase WAITING), so it holds
 * the other users up by one scene at most, as any job; a failed step of it is tried once more. What a turn does is
 * {@link JobRunner}.
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
    private final GpuGuest guest;
    private final GpuHolds holds;
    private final JobRunner runner;

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
            repository.save(job);
            queued.addLast(job);
            // by the turns of the users, as the worker takes them
            int pending = plannedOrder().indexOf(job) + (running != null ? 1 : 0);
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
                job.setProgress(null);
                repository.saveQuietly(job);
                // a long video between two scenes has some made already
                if (job.hasScenes()) {
                    discardScenes(job);
                }
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
        boolean unloaded = false;
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
                unloaded = true;
                log.info("Model unloaded for a GPU guest");
            }
            guests++;
            guestLoaded = true;
        } finally {
            lock.unlock();
        }
        // the idle unload skips a queue with nothing loaded, so the translation model leaves with the model; it is on
        // the CPU, so the queue does not wait for Ollama
        if (unloaded) {
            translator.unload();
        }
        return true;
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
            // under the lock: the guest must be gone before a job loads its model
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
            log.info("Model unloaded, {} after the last job", Duration.between(lastActivity, clock.instant()));
        } finally {
            lock.unlock();
        }
        // on the CPU: the queue does not wait for Ollama
        translator.unload();
        return true;
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
                // a long video back for its next scene started with its first one
                if (job.getStartedAt() == null) {
                    job.setStartedAt(clock.instant());
                }
                job.setProgress(job.hasScenes()
                        ? Progress.LOADING.inScene(nextScene(job), job.getScenes().size())
                        : Progress.LOADING);
                repository.saveQuietly(job);
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
        Instant now = clock.instant();
        Deadline deadline = new Deadline(now.plus(properties.jobTimeout()), properties.stallTimeout(), now, holds);
        JobResult result = null;
        String error = null;
        try {
            result = runner.run(job, deadline);
        } catch (JobFailedException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.error("Job {} failed unexpectedly", job.getId(), e);
            error = "internal error";
        }

        lock.lock();
        try {
            if (job.hasScenes() && againLater(job, result, error)) {
                return;
            }
            if (job.hasScenes() && result == null) {
                discardScenes(job);
            }
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

    /**
     * A long video that is not done goes back into the queue for its next step: after a scene that is not the last,
     * or after its first failure (the step is tried again). Under the lock.
     *
     * @return true when the job waits in the queue again
     */
    private boolean againLater(Job job, JobResult result, String error) {
        if (job.isCancelRequested() || result != null) {
            return false;
        }
        if (error != null) {
            if (job.getStepFailures() > 0) {
                return false;
            }
            job.setStepFailures(1);
            log.warn("Job {}: scene {} failed ({}), it is tried once more", job.getId(), job.getScenesDone() + 1,
                    error);
        }
        int count = job.getScenes().size();
        job.setProgress(new Progress(Progress.Phase.WAITING, 0, 0).inScene(nextScene(job), count));
        running = null;
        lastActivity = clock.instant();
        // At the head: it stays the oldest job of its user, so a later job of the same user does not get in between
        // its scenes; the other users still go first by their turns
        queued.addFirst(job);
        repository.saveQuietly(job);
        log.info("Job {}: {} of {} scenes made, back in the queue", job.getId(), job.getScenesDone(), count);
        return true;
    }

    /** The scenes are only files: a failed delete must not keep the job from ending */
    private void discardScenes(Job job) {
        try {
            gateway.discardScenes(job);
        } catch (RuntimeException e) {
            log.warn("Job {}: its scenes could not be deleted: {}", job.getId(), e.getMessage());
        }
    }

    /** The scene of a long video its next turn makes; the last one again when only the join is left */
    private static int nextScene(Job job) {
        return Math.min(job.getScenesDone() + 1, job.getScenes().size());
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
