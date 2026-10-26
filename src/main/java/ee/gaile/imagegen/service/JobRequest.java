package ee.gaile.imagegen.service;

import java.util.Map;
import java.util.UUID;

/**
 * The "request" part of POST /api/jobs.
 *
 * @param sourceJobId a DONE image job of the same user, used as the input image instead of the "image" part
 */
public record JobRequest(String model, Map<String, Object> params, UUID sourceJobId) {
}
