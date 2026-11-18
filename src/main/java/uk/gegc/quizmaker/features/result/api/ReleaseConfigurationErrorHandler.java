package uk.gegc.quizmaker.features.result.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException;
import uk.gegc.quizmaker.shared.api.problem.*;

@RestControllerAdvice(assignableTypes = ReleaseConfigurationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReleaseConfigurationErrorHandler {
    @ExceptionHandler(ReleaseConfigurationException.class)
    public ResponseEntity<ProblemDetail> failure(ReleaseConfigurationException failure, HttpServletRequest request) {
        var status = switch (failure.reason()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case INVALID_SCHEDULE, UNSUPPORTED_VERSION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STALE, CONFLICT -> HttpStatus.CONFLICT;
            case CORRUPT -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UNAVAILABLE, UNKNOWN_OUTCOME -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, failure.reason().name(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> malformed(HttpServletRequest request) { return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", request); }
    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String reason, HttpServletRequest request) {
        var type = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED;
            case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case CONFLICT -> ErrorTypes.ILLEGAL_STATE;
            default -> status.is5xxServerError() ? ErrorTypes.PROCESSING_FAILED : ErrorTypes.INVALID_ARGUMENT;
        };
        var body = ProblemDetailBuilder.create(status, type,
                status.getReasonPhrase(), "Release configuration is unavailable", request);
        body.setProperty("code", "RELEASE_CONFIGURATION_" + reason);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore()).body(body);
    }
}
