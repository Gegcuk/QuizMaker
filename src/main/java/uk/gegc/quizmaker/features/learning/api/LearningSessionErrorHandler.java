package uk.gegc.quizmaker.features.learning.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gegc.quizmaker.features.learning.domain.model.*;
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
        return problem(status, request);
    }

    @ExceptionHandler(LearningFormException.class)
    public ResponseEntity<ProblemDetail> formFailure(LearningFormException failure, HttpServletRequest request) {
        return problem(switch (failure.reason()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNAVAILABLE, SOURCE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
            case UNSUPPORTED_VERSION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT -> HttpStatus.CONFLICT;
            case CORRUPT, UNSUPPORTED_TRANSACTION -> HttpStatus.INTERNAL_SERVER_ERROR;
            case PERSISTENCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        }, request);
    }

    @ExceptionHandler(LearningScheduleException.class)
    public ResponseEntity<ProblemDetail> scheduleFailure(LearningScheduleException failure, HttpServletRequest request) {
        return problem(switch (failure.reason()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case UNAVAILABLE, SOURCE_UNAVAILABLE, SESSION_UNAVAILABLE -> HttpStatus.NOT_FOUND;
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED_MANIFEST, UNSUPPORTED_VERSION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT, ACTIVE_SESSION, STALE_STATE, WRONG_SCHEDULE, SESSION_INCOMPLETE, SESSION_CLOSED -> HttpStatus.CONFLICT;
            case PERSISTENCE_UNAVAILABLE, SELECTION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        }, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, DatabaseConflictException.class})
    public ResponseEntity<ProblemDetail> databaseFailure(RuntimeException failure, HttpServletRequest request) {
        var conflict = DatabaseConflictException.from(failure);
        if (conflict.isPresent() && conflict.get().kind() == DatabaseConflictException.Kind.LOCK_TIMEOUT) {
            return problem(HttpStatus.CONFLICT, ErrorTypes.DATA_CONFLICT,
                    "A concurrent operation is in progress. Retry the original request.", request);
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, HttpServletRequest request) {
        var type = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED;
            case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case CONFLICT -> ErrorTypes.IDEMPOTENCY_CONFLICT;
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> ErrorTypes.INVALID_ARGUMENT;
            default -> ErrorTypes.PROCESSING_FAILED;
        };
        return problem(status, type, status.getReasonPhrase(), request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type, String detail, HttpServletRequest request) {
        var body = ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), detail, request);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
