package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.ComfyGateway;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.gpu.UnloadPolicy;
import ee.gaile.imagegen.image.InputImages;
import ee.gaile.imagegen.model.ModelCatalogue;
import ee.gaile.imagegen.model.ModelDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Accepts a job: checks the request, prepares the input images, hands the job to the queue. Everything that can be
 * refused is refused before the first file is written.
 */
@Service
@RequiredArgsConstructor
public class JobService {

    private final ModelCatalogue catalogue;
    private final JobFactory factory;
    private final SourceImages sources;
    private final JobFiles files;
    private final JobQueue queue;
    private final UnloadPolicy unloadPolicy;
    private final ComfyGateway gateway;
    private final ObjectMapper mapper;

    /**
     * @param requestJson the "request" part of the multipart request
     * @param image       the uploaded photo or start frame, null when there is none
     * @param mask        the uploaded mask, null when there is none
     */
    public Accepted submit(long userId, String userName, String requestJson, byte[] image, byte[] mask) {
        JobRequest request = parse(requestJson);
        ModelDefinition model = catalogue.find(request.model())
                .orElseThrow(() -> new InvalidParamsException("Unknown model: " + request.model()));
        // Refused before the (slow) decoding of the photo
        factory.validate(model, request.params());
        if (!gateway.isUp()) {
            throw new ComfyUnavailableException("ComfyUI does not answer");
        }

        UUID id = UUID.randomUUID();
        BufferedImage picture = null;
        BufferedImage maskPicture = null;
        Dimension size = null;
        Job source = request.sourceJobId() != null ? sources.find(userId, request.sourceJobId()) : null;
        // A video waits in the queue behind the picture it starts from, which is written as its input then
        boolean pendingSource = source != null && !source.getStatus().isFinished();
        if (pendingSource && model.kind() != JobKind.VIDEO) {
            throw new InvalidParamsException("sourceJobId has not finished: only a video can wait for it");
        }
        boolean imageGiven = source != null || (image != null && image.length > 0);
        if (!pendingSource && (model.needsImage() || (model.acceptsImage() && imageGiven))) {
            picture = prepareImage(model, source, image);
            if (model.kind() != JobKind.VIDEO) {
                size = InputImages.editSize(picture.getWidth(), picture.getHeight());
                picture = InputImages.scale(picture, size.width, size.height);
            } else {
                picture = InputImages.limitSide(picture, InputImages.MAX_VIDEO_SIDE);
            }
        }
        if (model.needsMask()) {
            if (mask == null || mask.length == 0) {
                throw new InvalidParamsException("The mask is missing");
            }
            maskPicture = InputImages.mask(mask, picture.getWidth(), picture.getHeight());
        }
        Job job = factory.create(id, userId, userName, model, request.params(), size,
                picture != null || pendingSource);
        if (pendingSource) {
            job.setSourceJobId(source.getId());
        }

        writeInputs(id, picture, maskPicture);
        try {
            unloadPolicy.beforeSubmit(userId);
            int position = queue.submit(job);
            return new Accepted(job, position);
        } catch (RuntimeException e) {
            files.deleteAll(id);
            throw e;
        }
    }

    /**
     * @param source a finished image job of the user, or null
     * @return the picture of the source, or the uploaded image
     */
    private BufferedImage prepareImage(ModelDefinition model, Job source, byte[] upload) {
        if (source != null) {
            return InputImages.decode(sources.read(source));
        }
        if (upload == null || upload.length == 0) {
            throw new InvalidParamsException(model.id() + " needs an image");
        }
        return InputImages.decode(upload);
    }

    private void writeInputs(UUID id, BufferedImage picture, BufferedImage mask) {
        try {
            if (picture != null) {
                InputImages.writePng(picture, files.input(id));
            }
            if (mask != null) {
                InputImages.writePng(mask, files.mask(id));
            }
        } catch (UncheckedIOException e) {
            files.deleteAll(id);
            throw e;
        }
    }

    private JobRequest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidParamsException("The request part is missing");
        }
        try {
            JobRequest request = mapper.readValue(json, JobRequest.class);
            if (request == null || request.model() == null) {
                throw new InvalidParamsException("The request has no model");
            }
            return request;
        } catch (RuntimeException e) {
            if (e instanceof InvalidParamsException invalid) {
                throw invalid;
            }
            throw new InvalidParamsException("The request is not valid JSON of the expected shape");
        }
    }

    /**
     * @param position jobs ahead of the new one, the running one included
     */
    public record Accepted(Job job, int position) {
    }
}
