package ee.gaile.imagegen.image;

/** The result of a job could not be made into a thumbnail (500 thumbnail_failed) */
public class ThumbnailFailedException extends RuntimeException {

    public ThumbnailFailedException(String message) {
        super(message);
    }
}
