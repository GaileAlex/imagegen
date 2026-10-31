package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.model.ModelDefinition;
import ee.gaile.imagegen.model.ParamDefinition;
import ee.gaile.imagegen.model.config.VideoPromptStyle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Dimension;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Turns the params of a request into a {@link Job}: checks them against the catalogue, fills in the defaults and
 * the random seed, takes the size of an edited photo. What the workflow needs beyond that (frames, file names) is
 * derived by {@link WorkflowValues} when the job runs.
 */
@Component
public class JobFactory {

    public static final long MAX_SEED = Integer.MAX_VALUE;
    /** Service param of a video made from the text alone: the workflow skips its start image */
    public static final String TEXT_ONLY = "textOnly";
    /** The scenes of a 30 s video written out take ~5000 characters */
    private static final int MAX_TEXT_LENGTH = 12000;
    /** A scene shorter than this is mostly the frames it starts from */
    static final int MIN_SCENE_SECONDS = 2;
    /** Float params are typed by hand, so a step of 0.1 is not checked to the last bit */
    private static final double EPSILON = 1e-9;

    private final Clock clock;
    private final LongSupplier randomSeed;

    @Autowired
    public JobFactory(Clock clock) {
        this(clock, () -> ThreadLocalRandom.current().nextLong(MAX_SEED + 1));
    }

    public JobFactory(Clock clock, LongSupplier randomSeed) {
        this.clock = clock;
        this.randomSeed = randomSeed;
    }

    /**
     * A job with its input image, if the mode takes one.
     */
    public Job create(UUID id, long userId, String userName, ModelDefinition model, Map<String, Object> raw,
                      Dimension photoSize) {
        return create(id, userId, userName, model, raw, photoSize, model.acceptsImage());
    }

    /**
     * @param photoSize size of the prepared photo, needed (and only used) for EDIT and INPAINT
     * @param withImage the job has an input image; a video without one is marked {@value #TEXT_ONLY}
     * @throws InvalidParamsException an unknown param, a missing required one or a value out of its range
     */
    public Job create(UUID id, long userId, String userName, ModelDefinition model, Map<String, Object> raw,
                      Dimension photoSize, boolean withImage) {
        Map<String, Object> params = validate(model, raw);
        if (model.acceptsImage() && !withImage) {
            if (model.needsImage()) {
                throw new InvalidParamsException(model.id() + " needs an image");
            }
            params.put(TEXT_ONLY, true);
        }
        if (model.kind() == JobKind.EDIT || model.kind() == JobKind.INPAINT) {
            if (photoSize == null) {
                throw new InvalidParamsException("The photo is missing");
            }
            params.put("width", photoSize.width);
            params.put("height", photoSize.height);
        }
        List<Scene> scenes = model.kind() == JobKind.VIDEO ? scenes(model, params) : null;
        long seed = (Long) params.get("seed");
        Job job = new Job(id, userId, userName, model.id(), model.kind(), params, seed, clock.instant());
        job.setScenes(scenes);
        return job;
    }

    /**
     * A long video: the scenes written out in the prompt (their seconds become the length), or even scenes whose
     * prompts the job writes itself when the length is above one clip. Scenes written for another model (LTX scenes
     * of 10 s given to Wan, which takes 5 s a scene and 20 s in all; or the short soundless scenes of Wan given to
     * LTX, their marks naming the style) are written again by the job from the same text: as long as the length set
     * in the form when that is longer than the scenes (the 20 s of Wan on LTX set to 30 s make 3 x 10 s), never
     * longer than this model allows.
     *
     * @return null for a single clip
     */
    private List<Scene> scenes(ModelDefinition model, Map<String, Object> params) {
        List<Scene> marked = ScenePrompts.parse((String) params.get("prompt"));
        if (marked.size() == 1) {
            // one scene is a plain clip
            params.put("prompt", marked.getFirst().prompt());
        }
        int seconds = params.get("seconds") instanceof Number number ? number.intValue() : 0;
        if (marked.size() > 1) {
            int total = marked.stream().mapToInt(Scene::seconds).sum();
            int longest = maxSeconds(model);
            VideoPromptStyle style = ScenePrompts.style((String) params.get("prompt"));
            boolean fits = (style == null || style == model.promptStyle()) && total <= longest
                    && marked.stream().allMatch(scene ->
                    scene.seconds() >= MIN_SCENE_SECONDS && scene.seconds() <= model.sceneSeconds());
            if (fits) {
                params.put("seconds", total);
                return List.copyOf(marked);
            }
            seconds = Math.clamp(Math.max(total, seconds), MIN_SCENE_SECONDS, longest);
            params.put("seconds", seconds);
            if (seconds <= model.clipSeconds()) {
                params.put("prompt", marked.stream().map(Scene::prompt).collect(Collectors.joining("\n")));
                return null;
            }
        }
        if (seconds <= model.clipSeconds()) {
            return null;
        }
        return ScenePrompts.split(seconds, model.sceneSeconds()).stream()
                .map(length -> new Scene(length, null, null))
                .toList();
    }

    private static int maxSeconds(ModelDefinition model) {
        return model.params().stream()
                .filter(param -> param.name().equals("seconds") && param.max() != null)
                .map(param -> param.max().intValue())
                .findFirst()
                .orElse(model.clipSeconds());
    }

    /**
     * @return all the params of the mode with the defaults and the seed filled in
     */
    Map<String, Object> validate(ModelDefinition model, Map<String, Object> raw) {
        Map<String, Object> given = raw != null ? raw : Map.of();
        Set<String> unknown = new HashSet<>(given.keySet());
        model.params().forEach(param -> unknown.remove(param.name()));
        if (!unknown.isEmpty()) {
            throw new InvalidParamsException("Unknown param: " + unknown.iterator().next());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        for (ParamDefinition param : model.params()) {
            Object value = given.get(param.name());
            params.put(param.name(), switch (param.type()) {
                case TEXT -> text(param, value);
                case INT -> integer(param, value);
                case FLOAT -> decimal(param, value);
                case ENUM -> option(param, value);
                case SEED -> seed(value);
                case BOOL -> bool(param, value);
            });
        }
        return params;
    }

    private Object text(ParamDefinition param, Object value) {
        if (value == null || value instanceof String string && string.isBlank()) {
            if (param.required() != null) {
                throw new InvalidParamsException(param.name() + " is required");
            }
            return param.defaultValue();
        }
        if (!(value instanceof String string)) {
            throw new InvalidParamsException(param.name() + " must be text");
        }
        if (string.length() > MAX_TEXT_LENGTH) {
            throw new InvalidParamsException(param.name() + " is longer than " + MAX_TEXT_LENGTH + " characters");
        }
        return string;
    }

    private Object integer(ParamDefinition param, Object value) {
        if (value == null) {
            return param.defaultValue();
        }
        double number = number(param, value);
        if (number != Math.rint(number)) {
            throw new InvalidParamsException(param.name() + " must be a whole number");
        }
        checkRange(param, number);
        if (param.step() != null && param.min() != null) {
            double steps = (number - param.min().doubleValue()) / param.step().doubleValue();
            if (Math.abs(steps - Math.rint(steps)) > EPSILON) {
                throw new InvalidParamsException(param.name() + " must be a multiple of " + param.step()
                        + " from " + param.min());
            }
        }
        return (int) number;
    }

    private Object decimal(ParamDefinition param, Object value) {
        if (value == null) {
            return param.defaultValue();
        }
        double number = number(param, value);
        checkRange(param, number);
        return number;
    }

    private Object option(ParamDefinition param, Object value) {
        if (value == null) {
            return param.defaultValue();
        }
        if (!(value instanceof String string) || !param.options().contains(string)) {
            throw new InvalidParamsException(param.name() + " must be one of " + param.options());
        }
        return string;
    }

    private Object seed(Object value) {
        if (value == null || value instanceof String string && string.isBlank()) {
            return randomSeed.getAsLong();
        }
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > MAX_SEED) {
            throw new InvalidParamsException("seed must be a whole number from 0 to " + MAX_SEED + " or empty");
        }
        return number.longValue();
    }

    private Object bool(ParamDefinition param, Object value) {
        if (value == null) {
            return param.defaultValue();
        }
        if (!(value instanceof Boolean)) {
            throw new InvalidParamsException(param.name() + " must be true or false");
        }
        return value;
    }

    private double number(ParamDefinition param, Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new InvalidParamsException(param.name() + " must be a number");
    }

    private void checkRange(ParamDefinition param, double number) {
        if (param.min() != null && number < param.min().doubleValue() - EPSILON
                || param.max() != null && number > param.max().doubleValue() + EPSILON) {
            throw new InvalidParamsException(param.name() + " must be between " + param.min() + " and " + param.max());
        }
    }
}
