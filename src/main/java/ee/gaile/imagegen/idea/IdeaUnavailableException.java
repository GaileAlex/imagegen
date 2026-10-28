package ee.gaile.imagegen.idea;

/** The idea model is not set, does not answer or answers nonsense (503 idea_unavailable) */
public class IdeaUnavailableException extends RuntimeException {

    public IdeaUnavailableException(String message) {
        super(message);
    }
}
