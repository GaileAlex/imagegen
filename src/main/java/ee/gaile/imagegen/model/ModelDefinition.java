package ee.gaile.imagegen.model;

import ee.gaile.imagegen.job.JobKind;

import java.util.List;

/**
 * A mode of the catalogue, the element of GET /api/models.
 *
 * @param needsImage   a photo or start image is uploaded (or taken from an earlier job)
 * @param acceptsImage a photo or start image may be given; true whenever needsImage is
 * @param needsMask    a mask is uploaded as well
 */
public record ModelDefinition(String id, JobKind kind, String title, String description, boolean needsImage,
                              boolean acceptsImage, boolean needsMask, String outputType, int typicalSeconds,
                              List<ParamDefinition> params) {
}
