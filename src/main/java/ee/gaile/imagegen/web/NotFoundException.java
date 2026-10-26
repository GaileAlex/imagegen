package ee.gaile.imagegen.web;

/** A job that does not exist, belongs to another user, or has no such file (404 not_found) */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
