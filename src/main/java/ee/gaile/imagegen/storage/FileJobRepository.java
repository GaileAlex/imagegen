package ee.gaile.imagegen.storage;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.config.ImagegenProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The job records as json files next to the results, all loaded into memory at the start: no database, and a record
 * can never outlive its files. The index holds the very {@link Job} objects the queue changes, so a status read from
 * here is always current.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileJobRepository implements JobStore {

    private final JobFiles files;
    private final ImagegenProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    private final Map<UUID, Job> index = new ConcurrentHashMap<>();

    /** Jobs that were queued or running when the service stopped will never finish */
    @PostConstruct
    public void load() {
        int restarted = 0;
        try (DirectoryStream<Path> records = Files.newDirectoryStream(files.directory(), "*.json")) {
            for (Path file : records) {
                try {
                    Job job = mapper.readValue(Files.readAllBytes(file), JobRecord.class).toJob();
                    index.put(job.getId(), job);
                    if (!job.getStatus().isFinished()) {
                        job.setStatus(JobStatus.FAILED);
                        job.setError("service restarted");
                        job.setFinishedAt(clock.instant());
                        save(job);
                        restarted++;
                    }
                } catch (RuntimeException | IOException e) {
                    log.warn("Skipped the unreadable record {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + files.directory(), e);
        }
        log.info("Loaded {} job records, {} failed by the restart", index.size(), restarted);
    }

    /** The file is written aside and moved over the old one, so a crash leaves a whole record */
    @Override
    public synchronized void save(Job job) {
        Path target = files.record(job.getId());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, mapper.writeValueAsBytes(JobRecord.of(job)));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the record of job " + job.getId(), e);
        }
        index.put(job.getId(), job);
    }

    @Override
    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(index.get(id));
    }

    @Override
    public List<Job> findByUser(long userId, int limit) {
        Instant oldest = clock.instant().minus(properties.retention());
        return index.values().stream()
                .filter(job -> job.getUserId() == userId)
                .filter(job -> job.getCreatedAt().isAfter(oldest))
                .sorted(Comparator.comparing(Job::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void delete(UUID id) {
        index.remove(id);
        files.deleteAll(id);
    }

    /** Jobs finished more than the retention ago go with all their files */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void cleanup() {
        Instant limit = clock.instant().minus(properties.retention());
        List<UUID> expired = index.values().stream()
                .filter(job -> job.getFinishedAt() != null && job.getFinishedAt().isBefore(limit))
                .map(Job::getId)
                .toList();
        expired.forEach(this::delete);
        if (!expired.isEmpty()) {
            log.info("Deleted {} jobs finished more than {} ago", expired.size(), properties.retention());
        }
    }
}
