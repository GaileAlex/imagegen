package ee.gaile.imagegen.service;

/** The request cannot be run: unknown mode, a param out of range, no or an unreadable image (400 invalid_params) */
public class InvalidParamsException extends RuntimeException {

    public InvalidParamsException(String message) {
        super(message);
    }
}
