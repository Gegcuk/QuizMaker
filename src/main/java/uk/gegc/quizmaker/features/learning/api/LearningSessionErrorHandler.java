package uk.gegc.quizmaker.features.learning.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "uk.gegc.quizmaker.features.learning")
public class LearningSessionErrorHandler {
    @ExceptionHandler(LearningSessionException.class)
    public ResponseEntity<ProblemDetail> sessionFailure(LearningSessionException failure, HttpServletRequest request) {
        var status = switch (failure.reason()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case UNAVAILABLE, SOURCE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED_VERSION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PERSISTENCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CORRUPT, UNSUPPORTED_TRANSACTION -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var type = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED;
            case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case CONFLICT -> ErrorTypes.IDEMPOTENCY_CONFLICT;
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> ErrorTypes.INVALID_ARGUMENT;
            default -> ErrorTypes.PROCESSING_FAILED;
        };
        var body = ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), status.getReasonPhrase(), request);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
