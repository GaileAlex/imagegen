package ee.gaile.imagegen.service;

import java.util.Map;
import java.util.UUID;

/**
 * The "request" part of POST /api/jobs.
 *
 * @param sourceJobId an image job of the same user, its picture is the input image instead of the "image" part;
 *                    for a video it may still be queued or running
 */
public record JobRequest(String model, Map<String, Object> params, UUID sourceJobId) {
}
