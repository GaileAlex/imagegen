package ee.gaile.imagegen.job;

public class JobFailedException extends RuntimeException {

    public JobFailedException(String message) {
        super(message);
    }

    public JobFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
