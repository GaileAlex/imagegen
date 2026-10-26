package ee.gaile.imagegen.web.dto;

/**
 * The body of every error answer: {"error": "invalid_params", "message": "..."}.
 */
public record ErrorBody(String error, String message) {
}
