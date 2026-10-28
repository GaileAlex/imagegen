package ee.gaile.imagegen.model.config;

import ee.gaile.imagegen.job.JobKind;

import java.util.List;

/**
 * A mode of the catalogue as written in application.yml (imagegen.models).
 *
 * @param imageOptional a VIDEO mode that also runs from the text alone, without a start image
 * @param promptStyle   how the prompt of a VIDEO mode is written from an idea (required for VIDEO, absent otherwise)
 */
public record ModelConfig(String id, JobKind kind, String title, String description, int typicalSeconds,
                          boolean imageOptional, VideoPromptStyle promptStyle,
                          List<ParamConfig> params) {
}
