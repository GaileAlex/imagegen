package ee.gaile.imagegen.model.config;

import ee.gaile.imagegen.job.JobKind;

import java.util.List;

/**
 * A mode of the catalogue as written in application.yml (imagegen.models).
 *
 * @param imageOptional a VIDEO mode that also runs from the text alone, without a start image
 */
public record ModelConfig(String id, JobKind kind, String title, String description, int typicalSeconds,
                          boolean imageOptional, List<ParamConfig> params) {
}
