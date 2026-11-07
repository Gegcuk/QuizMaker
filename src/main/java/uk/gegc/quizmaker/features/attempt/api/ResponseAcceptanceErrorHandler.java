package uk.gegc.quizmaker.features.attempt.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.shared.api.problem.*;

@RestControllerAdvice(assignableTypes = ResponseAcceptanceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResponseAcceptanceErrorHandler {
    @ExceptionHandler(ResponseAcceptanceException.class)
    public ResponseEntity<ProblemDetail> handle(ResponseAcceptanceException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.reason()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_INPUT, UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT, STALE, INACTIVE, IN_PROGRESS, EXPIRED -> HttpStatus.CONFLICT;
            case CORRUPT -> HttpStatus.INTERNAL_SERVER_ERROR;
            case RETRYABLE, UNKNOWN_OUTCOME, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, "RESPONSE_" + failure.reason().name(), request);
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class, ReceiptException.class,
            ResponseAcceptanceJsonConfig.InvalidRequest.class})
    public ResponseEntity<ProblemDetail> malformed(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_RESPONSE_REQUEST", request);
    }
    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code, HttpServletRequest request) {
        var type = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED; case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> ErrorTypes.INVALID_ARGUMENT; case CONFLICT -> ErrorTypes.ILLEGAL_STATE;
            default -> ErrorTypes.PROCESSING_FAILED;
        };
        var body = ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), "Response command could not be accepted", request);
        body.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
