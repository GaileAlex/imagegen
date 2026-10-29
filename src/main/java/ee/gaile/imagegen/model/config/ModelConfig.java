package ee.gaile.imagegen.model.config;

import ee.gaile.imagegen.job.JobKind;

import java.util.List;

/**
 * A mode of the catalogue as written in application.yml (imagegen.models).
 *
 * @param imageOptional a VIDEO mode that also runs from the text alone, without a start image
 * @param promptStyle   how the prompt of a VIDEO mode is written from an idea (required for VIDEO, absent otherwise)
 * @param clipSeconds   VIDEO: the longest clip made in one run; a longer video is made of scenes
 * @param sceneSeconds  VIDEO: the longest scene of a long video (its start frames not counted)
 */
public record ModelConfig(String id, JobKind kind, String title, String description, int typicalSeconds,
                          boolean imageOptional, VideoPromptStyle promptStyle, Integer clipSeconds,
                          Integer sceneSeconds, List<ParamConfig> params) {
}
