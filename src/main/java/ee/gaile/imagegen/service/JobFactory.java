package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.model.ModelDefinition;
import ee.gaile.imagegen.model.ParamDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Dimension;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Turns the params of a request into a {@link Job}: checks them against the catalogue, fills in the defaults and
 * the random seed, takes the size of an edited photo. What the workflow needs beyond that (frames, file names) is
 * derived by {@link WorkflowValues} when the job runs.
 */
@Component
public class JobFactory {

    public static final long MAX_SEED = Integer.MAX_VALUE;
    private static final int MAX_TEXT_LENGTH = 4000;
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
     * @param photoSize size of the prepared photo, needed (and only used) for EDIT and INPAINT
     * @throws InvalidParamsException an unknown param, a missing required one or a value out of its range
     */
    public Job create(UUID id, long userId, String userName, ModelDefinition model, Map<String, Object> raw,
                      Dimension photoSize) {
        Map<String, Object> params = validate(model, raw);
        if (model.kind() == JobKind.EDIT || model.kind() == JobKind.INPAINT) {
            if (photoSize == null) {
                throw new InvalidParamsException("The photo is missing");
            }
            params.put("width", photoSize.width);
            params.put("height", photoSize.height);
        }
        long seed = (Long) params.get("seed");
        return new Job(id, userId, userName, model.id(), model.kind(), params, seed, clock.instant());
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
