package ee.gaile.imagegen.web;

import ee.gaile.imagegen.idea.IdeaUnavailableException;
import ee.gaile.imagegen.image.ThumbnailFailedException;
import ee.gaile.imagegen.web.dto.ErrorBody;
import ee.gaile.imagegen.service.ComfyUnavailableException;
import ee.gaile.imagegen.service.InvalidParamsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * The error bodies of docs/CONTRACT.md: {"error": code, "message": text}.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidParamsException.class)
    public ResponseEntity<ErrorBody> invalidParams(InvalidParamsException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_params", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> missingHeader(MissingRequestHeaderException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_params", "The header " + e.getHeaderName() + " is required");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorBody> missingPart(MissingServletRequestPartException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_params", "The part " + e.getRequestPartName() + " is required");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorBody> missingParameter(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_params", "The parameter " + e.getParameterName() + " is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> badArgument(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_params", e.getName() + " has a wrong format");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_params", "A file is larger than 20 MB");
    }

    @ExceptionHandler(ComfyUnavailableException.class)
    public ResponseEntity<ErrorBody> comfyDown(ComfyUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "comfyui_unavailable", e.getMessage());
    }

    @ExceptionHandler(IdeaUnavailableException.class)
    public ResponseEntity<ErrorBody> ideaUnavailable(IdeaUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "idea_unavailable", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorBody> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    @ExceptionHandler(ThumbnailFailedException.class)
    public ResponseEntity<ErrorBody> thumbnailFailed(ThumbnailFailedException e) {
        log.warn("Thumbnail not made: {}", e.getMessage());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "thumbnail_failed", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    private ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message));
    }
}
