package ee.gaile.imagegen.model;

import ee.gaile.imagegen.model.config.CatalogueProperties;
import ee.gaile.imagegen.model.config.ModelConfig;
import ee.gaile.imagegen.model.config.ParamConfig;
import ee.gaile.imagegen.model.workflow.ParamSpec;
import ee.gaile.imagegen.model.workflow.WorkflowRepository;
import ee.gaile.imagegen.model.workflow.WorkflowTemplate;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.service.WorkflowValues;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The modes the service offers: application.yml says which and with what client params, the params files next to the
 * workflows give the defaults. A mistake in the settings stops the start instead of failing the first job.
 */
@Component
public class ModelCatalogue {

    private final Map<String, ModelDefinition> models = new LinkedHashMap<>();

    public ModelCatalogue(CatalogueProperties properties, WorkflowRepository workflows) {
        for (ModelConfig config : properties.models()) {
            WorkflowTemplate template = workflows.get(config.id());
            ModelDefinition model = build(config, template);
            if (models.put(model.id(), model) != null) {
                throw new IllegalStateException("Mode " + model.id() + " is listed twice");
            }
        }
    }

    public List<ModelDefinition> list() {
        return List.copyOf(models.values());
    }

    public Optional<ModelDefinition> find(String id) {
        return Optional.ofNullable(models.get(id));
    }

    private ModelDefinition build(ModelConfig config, WorkflowTemplate template) {
        List<ParamDefinition> params = new ArrayList<>();
        for (ParamConfig param : config.params()) {
            ParamSpec spec = template.params().get(param.name());
            if (spec == null && !WorkflowValues.CLIENT_ONLY_PARAMS.contains(param.name())) {
                throw new IllegalStateException(config.id() + ": param " + param.name() + " is not in its params file");
            }
            params.add(buildParam(config.id(), param, spec));
        }
        JobKind kind = config.kind();
        boolean acceptsImage = kind != JobKind.IMAGE;
        if (config.imageOptional() && kind != JobKind.VIDEO) {
            throw new IllegalStateException(config.id() + ": only a VIDEO mode can have an optional image");
        }
        boolean needsImage = acceptsImage && !config.imageOptional();
        boolean needsMask = kind == JobKind.INPAINT;
        String outputType = kind == JobKind.VIDEO ? "video/mp4" : "image/png";
        return new ModelDefinition(config.id(), kind, config.title(), config.description(), needsImage, acceptsImage,
                needsMask, outputType, config.typicalSeconds(), List.copyOf(params));
    }

    private ParamDefinition buildParam(String modelId, ParamConfig param, ParamSpec spec) {
        Object defaultValue = param.type() == ParamType.SEED ? null : defaultOf(modelId, param, spec);
        Number min = number(param, param.min());
        Number max = number(param, param.max());
        Number step = number(param, param.step());
        if (param.type() == ParamType.ENUM) {
            if (param.options() == null || param.options().isEmpty()) {
                throw new IllegalStateException(modelId + ": ENUM " + param.name() + " has no options");
            }
            if (!param.options().contains(defaultValue)) {
                throw new IllegalStateException(modelId + ": default of " + param.name() + " is not an option");
            }
        }
        if (min != null && max != null && defaultValue instanceof Number number
                && (number.doubleValue() < min.doubleValue() || number.doubleValue() > max.doubleValue())) {
            throw new IllegalStateException(modelId + ": default of " + param.name() + " is out of its range");
        }
        List<String> options = param.type() == ParamType.ENUM ? List.copyOf(param.options()) : null;
        return new ParamDefinition(param.name(), param.type(), defaultValue, min, max, step, options,
                param.required() ? Boolean.TRUE : null);
    }

    private Object defaultOf(String modelId, ParamConfig param, ParamSpec spec) {
        if (param.defaultValue() != null) {
            return convert(modelId, param, param.defaultValue());
        }
        JsonNode fromFile = spec != null ? spec.defaultValue() : null;
        if (fromFile == null || fromFile.isNull()) {
            throw new IllegalStateException(modelId + ": param " + param.name() + " has no default");
        }
        return convert(modelId, param, fromFile.asString());
    }

    private Object convert(String modelId, ParamConfig param, String text) {
        try {
            return switch (param.type()) {
                case INT -> Integer.valueOf(text);
                case FLOAT -> Double.valueOf(text);
                default -> text;
            };
        } catch (NumberFormatException e) {
            throw new IllegalStateException(modelId + ": default of " + param.name() + " is not a number: " + text);
        }
    }

    /** INT params show whole numbers (8, not 8.0) */
    private Number number(ParamConfig param, Double value) {
        if (value == null) {
            return null;
        }
        return param.type() == ParamType.INT ? (Number) value.intValue() : value;
    }
}
