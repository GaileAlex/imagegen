package ee.gaile.imagegen.web;

/** A request the state of the job does not allow, e.g. the delete of a pinned job (409 with the code) */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
