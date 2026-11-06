package uk.gegc.quizmaker.features.attempt.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;

@RestControllerAdvice(assignableTypes = AttemptFormController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AttemptDeliveryErrorHandler {
    @ExceptionHandler(AttemptDeliveryException.class)
    public ResponseEntity<ProblemDetail> handle(AttemptDeliveryException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.reason()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INACTIVE, BUSY -> HttpStatus.CONFLICT;
            case UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CORRUPT -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String detail = switch (failure.reason()) {
            case UNAUTHENTICATED -> "Authentication is required";
            case NOT_FOUND -> "Attempt is unavailable";
            case INACTIVE -> "Attempt is not active";
            case BUSY -> "Attempt is currently busy";
            case UNSUPPORTED -> "Saved delivery contract is unsupported";
            case CORRUPT -> "Saved form is unavailable";
            case UNAVAILABLE -> "Attempt delivery is temporarily unavailable";
        };
        return response(status, detail, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> invalidId(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Invalid attempt identifier", request);
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String detail, HttpServletRequest request) {
        var type = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED;
            case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case BAD_REQUEST -> ErrorTypes.INVALID_ARGUMENT;
            case CONFLICT -> ErrorTypes.ILLEGAL_STATE;
            case UNPROCESSABLE_ENTITY -> ErrorTypes.UNSUPPORTED_OPERATION;
            default -> ErrorTypes.PROCESSING_FAILED;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), detail, request));
    }
}
