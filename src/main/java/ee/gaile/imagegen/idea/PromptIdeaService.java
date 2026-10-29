package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.idea.IdeaPrompts.Start;
import ee.gaile.imagegen.image.InputImages;
import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.model.ModelCatalogue;
import ee.gaile.imagegen.model.ModelDefinition;
import ee.gaile.imagegen.model.ParamDefinition;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.service.InvalidParamsException;
import ee.gaile.imagegen.service.ScenePrompts;
import ee.gaile.imagegen.service.SourceImages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Writes the prompts of a video from a short idea: the start frame and the video together, or the video for a
 * given picture (the idea may be empty then: the model chooses what happens from the picture), or a video from the
 * text alone. Nothing is queued: the page shows the prompts in its fields, the user may change them and starts the
 * generation as usual. A video longer than one clip gets its scenes written out in the video prompt (see
 * {@link ScenePrompts}), all from one answer, so they tell one story.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptIdeaService {

    static final int MAX_IDEA_LENGTH = 1000;
    /** The model looks at the picture this small: enough to tell what is in it, quick to encode on the CPU */
    static final int PICTURE_SIDE = 768;

    private final ModelCatalogue catalogue;
    private final SourceImages sources;
    private final IdeaWriter writer;
    private final JobQueue queue;
    private final ObjectMapper mapper;

    /**
     * @param requestJson the "request" part, an {@link IdeaRequest}
     * @param image       the start picture of the user, null when there is none or it is given as sourceJobId
     */
    public PromptIdea write(long userId, String requestJson, byte[] image) {
        IdeaRequest request = parse(requestJson);
        String idea = request.idea() == null ? "" : request.idea().strip();
        if (idea.length() > MAX_IDEA_LENGTH) {
            throw new InvalidParamsException("The idea is longer than " + MAX_IDEA_LENGTH + " characters");
        }
        ModelDefinition model = catalogue.find(request.videoModel())
                .filter(found -> found.kind() == JobKind.VIDEO)
                .orElseThrow(() -> new InvalidParamsException("Not a video model: " + request.videoModel()));
        int seconds = seconds(model, request.seconds());
        boolean portrait = "portrait".equals(request.orientation());

        Job source = request.sourceJobId() != null ? sources.find(userId, request.sourceJobId()) : null;
        if (source != null && !source.getStatus().isFinished()) {
            throw new InvalidParamsException("sourceJobId has no picture yet");
        }
        byte[] picture = source != null ? sources.read(source) : image;
        boolean withPicture = picture != null && picture.length > 0;
        Start start;
        if (Boolean.TRUE.equals(request.frame())) {
            start = Start.FRAME;
        } else if (withPicture) {
            // a picture is enough to go on: the model chooses the action itself
            start = idea.isEmpty() ? Start.PICTURE_ONLY : Start.PICTURE;
        } else if (!model.needsImage()) {
            start = Start.TEXT;
        } else {
            throw new InvalidParamsException(model.id() + " needs a start picture or a frame");
        }
        if (idea.isEmpty() && start != Start.PICTURE_ONLY) {
            throw new InvalidParamsException("The idea is empty");
        }

        // a video longer than one clip is made of scenes: their prompts come from one storyboard answer
        List<Integer> lengths = seconds > model.clipSeconds()
                ? ScenePrompts.split(seconds, model.sceneSeconds()) : null;
        String system = lengths != null
                ? IdeaPrompts.storyboard(start, model.promptStyle(), lengths, portrait)
                : IdeaPrompts.system(start, model.promptStyle(), seconds, portrait);
        String user = IdeaPrompts.user(idea, start.seesPicture() ? sourcePrompt(source) : null);
        Map<String, Object> schema = lengths != null
                ? IdeaPrompts.storyboardSchema(start, model.promptStyle(), lengths.size())
                : IdeaPrompts.schema(start);
        byte[] seen = start.seesPicture() ? small(picture) : null;
        long started = System.nanoTime();
        Map<String, String> answer = onGpu(system, user, seen, schema);
        String device = "GPU";
        if (answer == null) {
            answer = writer.write(system, user, seen, schema, false);
            device = "CPU";
        }
        log.info("Prompt idea for {} ({}, {} s) of user {} written on the {} in {} ms", model.id(), start, seconds,
                userId, device, (System.nanoTime() - started) / 1_000_000);
        if (start.seesPicture()) {
            log.debug("The idea model sees in the picture: {}", answer.get(IdeaPrompts.PICTURE));
        }
        if (lengths != null) {
            log.debug("The storyboard: {} | {}", answer.get(IdeaPrompts.CAST), answer.get(IdeaPrompts.STORY));
            boolean empty = start == Start.PICTURE_ONLY && IdeaPrompts.nobodyIn(answer.get(IdeaPrompts.LIVING));
            List<Scene> scenes = IdeaPrompts.scenes(answer, lengths, empty);
            return new PromptIdea(answer.get(IdeaPrompts.FRAME_PROMPT), ScenePrompts.format(scenes));
        }
        String videoPrompt = answer.get(IdeaPrompts.VIDEO_PROMPT);
        if (start == Start.PICTURE_ONLY) {
            log.debug("The idea model sees alive: {}; chose: {}", answer.get(IdeaPrompts.LIVING),
                    answer.get(IdeaPrompts.ACTION));
            if (IdeaPrompts.nobodyIn(answer.get(IdeaPrompts.LIVING))) {
                videoPrompt = IdeaPrompts.withoutAbsent(videoPrompt);
            }
        }
        return new PromptIdea(answer.get(IdeaPrompts.FRAME_PROMPT), videoPrompt);
    }

    /**
     * @return null when a job has the GPU, or the GPU instance failed: the CPU takes over then
     */
    private Map<String, String> onGpu(String system, String user, byte[] picture, Map<String, Object> schema) {
        if (!writer.hasGpu() || !queue.lendGpu()) {
            return null;
        }
        try {
            return writer.write(system, user, picture, schema, true);
        } catch (IdeaUnavailableException e) {
            log.warn("Prompt idea failed on the GPU, the CPU takes over: {}", e.getMessage());
            return null;
        } finally {
            queue.returnGpu();
        }
    }

    private IdeaRequest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidParamsException("The request part is missing");
        }
        try {
            IdeaRequest request = mapper.readValue(json, IdeaRequest.class);
            if (request == null || request.videoModel() == null) {
                throw new InvalidParamsException("The request has no videoModel");
            }
            return request;
        } catch (RuntimeException e) {
            if (e instanceof InvalidParamsException invalid) {
                throw invalid;
            }
            throw new InvalidParamsException("The request is not valid JSON of the expected shape");
        }
    }

    /** The asked length within the range of the mode, else its default */
    private static int seconds(ModelDefinition model, Integer asked) {
        ParamDefinition param = model.params().stream()
                .filter(candidate -> candidate.name().equals("seconds"))
                .findFirst()
                .orElse(null);
        if (param == null) {
            return asked != null ? asked : 5;
        }
        if (asked == null) {
            return ((Number) param.defaultValue()).intValue();
        }
        return Math.clamp(asked, param.min().intValue(), param.max().intValue());
    }

    /** The English prompt a generated picture was made from: the best hint of what it shows */
    static String sourcePrompt(Job source) {
        if (source == null || source.getKind() != JobKind.IMAGE) {
            return null;
        }
        String translated = source.getTranslations().get("prompt");
        if (translated != null) {
            return translated;
        }
        return source.getParams().get("prompt") instanceof String prompt ? prompt : null;
    }

    static byte[] small(byte[] picture) {
        BufferedImage image = InputImages.limitSide(InputImages.decode(picture), PICTURE_SIDE);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
