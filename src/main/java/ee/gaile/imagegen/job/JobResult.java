package ee.gaile.imagegen.job;

/**
 * The file a finished job produced, stored next to its record.
 *
 * @param durationSeconds video only, null for images
 */
public record JobResult(String contentType, long size, int width, int height, Double durationSeconds) {
}
