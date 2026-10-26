package ee.gaile.imagegen.job;

public class TooManyJobsException extends RuntimeException {

    public TooManyJobsException(String message) {
        super(message);
    }
}
