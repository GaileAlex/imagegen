package ee.gaile.imagegen.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.model.config.VideoPromptStyle;

import java.util.List;

/**
 * A mode of the catalogue, the element of GET /api/models.
 *
 * @param needsImage   a photo or start image is uploaded (or taken from an earlier job)
 * @param acceptsImage a photo or start image may be given; true whenever needsImage is
 * @param needsMask    a mask is uploaded as well
 * @param clipSeconds  VIDEO only: the longest clip made in one run; a longer one is made of scenes (a long video)
 * @param promptStyle  VIDEO only: the kind of prompt the model wants; for the prompt ideas, not for the page
 * @param sceneSeconds VIDEO only: the longest scene of a long video
 */
public record ModelDefinition(String id, JobKind kind, String title, String description, boolean needsImage,
                              boolean acceptsImage, boolean needsMask, String outputType, int typicalSeconds,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Integer clipSeconds,
                              List<ParamDefinition> params,
                              @JsonIgnore VideoPromptStyle promptStyle,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Integer sceneSeconds) {
}
