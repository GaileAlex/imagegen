package ee.gaile.imagegen.web;

import ee.gaile.imagegen.web.dto.JobView;
import ee.gaile.imagegen.web.dto.SubmitResponse;
import ee.gaile.imagegen.config.ImagegenProperties;
import ee.gaile.imagegen.service.InvalidParamsException;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.service.JobService;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.storage.JobStore;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * The jobs of docs/CONTRACT.md. Every job belongs to the X-User-Id that created it; another user's job answers 404
 * as if it did not exist.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final int MAX_LIST = 200;

    private final JobService service;
    private final JobStore store;
    private final JobQueue queue;
    private final JobFiles files;
    private final ImagegenProperties properties;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubmitResponse> submit(@RequestHeader("X-User-Id") long userId,
                                                 @RequestHeader(value = "X-User-Name", required = false) String userName,
                                                 @RequestPart("request") String request,
                                                 @RequestPart(value = "image", required = false) MultipartFile image,
                                                 @RequestPart(value = "mask", required = false) MultipartFile mask) {
        JobService.Accepted accepted = service.submit(userId, userName, request, bytes(image), bytes(mask));
        Job job = accepted.job();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SubmitResponse(job.getId(), job.getStatus(), accepted.position(), job.getSeed()));
    }

    @GetMapping("/{id}")
    public JobView get(@RequestHeader("X-User-Id") long userId, @PathVariable UUID id) {
        return view(own(userId, id));
    }

    @GetMapping
    public List<JobView> list(@RequestHeader("X-User-Id") long userId,
                              @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return store.findByUser(userId, bounded).stream().map(this::view).toList();
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<Resource> result(@RequestHeader("X-User-Id") long userId, @PathVariable UUID id,
                                           @RequestParam(defaultValue = "false") boolean download) {
        Job job = own(userId, id);
        if (job.getStatus() != JobStatus.DONE || job.getResult() == null) {
            throw new NotFoundException("The job has no result");
        }
        String contentType = job.getResult().contentType();
        Path file = files.result(id, contentType);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("The result file is gone");
        }
        String name = "%s-%s-%d.%s".formatted(job.getModel(), FILE_TIME.format(job.getFinishedAt()), job.getSeed(),
                JobFiles.extension(contentType));
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(name).build();
        // A finished result never changes, but it is the user's own picture: no shared caches
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(file));
    }

    @GetMapping("/{id}/input")
    public ResponseEntity<Resource> input(@RequestHeader("X-User-Id") long userId, @PathVariable UUID id) {
        Job job = own(userId, id);
        Path file = files.input(id);
        if (job.getKind() == JobKind.IMAGE || !Files.isRegularFile(file)) {
            throw new NotFoundException("The job has no input image");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(file));
    }

    /** Pending jobs are cancelled (the record stays), finished ones are deleted with their files, pinned ones refused */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-User-Id") long userId, @PathVariable UUID id) {
        Job job = own(userId, id);
        if (!job.getStatus().isFinished() && queue.cancel(job)) {
            return ResponseEntity.noContent().build();
        }
        if (job.isPinned()) {
            throw new ConflictException("pinned", "The job is pinned, unpin it first");
        }
        // Finished meanwhile: the cancel found nothing pending, so the delete below is what the user asked for
        if (job.getStatus().isFinished()) {
            store.delete(id);
        }
        return ResponseEntity.noContent().build();
    }

    /** Only a result can be pinned: a job without one has nothing to keep */
    @PutMapping("/{id}/pin")
    public JobView pin(@RequestHeader("X-User-Id") long userId, @PathVariable UUID id) {
        if (own(userId, id).getStatus() != JobStatus.DONE) {
            throw new ConflictException("not_done", "Only a finished job with a result can be pinned");
        }
        return view(store.pin(id, true).orElseThrow(() -> new NotFoundException("No such job")));
    }

    @DeleteMapping("/{id}/pin")
    public JobView unpin(@RequestHeader("X-User-Id") long userId, @PathVariable UUID id) {
        own(userId, id);
        return view(store.pin(id, false).orElseThrow(() -> new NotFoundException("No such job")));
    }

    private Job own(long userId, UUID id) {
        return store.find(id).filter(job -> job.getUserId() == userId)
                .orElseThrow(() -> new NotFoundException("No such job"));
    }

    private JobView view(Job job) {
        return JobView.of(job, job.getStatus().isFinished() ? -1 : queue.position(job), properties.retention());
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidParamsException("The upload could not be read");
        }
    }
}
