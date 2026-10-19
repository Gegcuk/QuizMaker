package uk.gegc.quizmaker.features.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthRateLimitUnavailableException;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;

import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;

@Order(-1)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthThrottleErrorHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail body = ProblemDetailBuilder.create(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION_FAILED,
                "Validation Failed", "Validation failed for one or more fields", request);
        // Preserve field names/messages and the existing error shape without reflecting passwords or tokens.
        body.setProperty("fieldErrors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage(), null)).toList());
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> malformedRequest(HttpMessageNotReadableException exception, HttpServletRequest request) {
        ProblemDetail body = ProblemDetailBuilder.create(HttpStatus.BAD_REQUEST, ErrorTypes.MALFORMED_JSON,
                "Malformed JSON", "Request body is malformed or cannot be read", request);
        // Parser messages can quote unrecognized credential text from malformed JSON.
        body.setProperty("parseError", "Request body is malformed or cannot be read");
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @ExceptionHandler(AuthRateLimitUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(AuthRateLimitUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), null, request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> throttled(RateLimitExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), exception.getRetryAfterSeconds(), request);
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, Long retryAfter, HttpServletRequest request) {
        ProblemDetail body = ProblemDetailBuilder.create(status,
                status == HttpStatus.TOO_MANY_REQUESTS ? ErrorTypes.RATE_LIMIT_EXCEEDED : ErrorTypes.AUTH_RATE_LIMIT_UNAVAILABLE,
                (status == HttpStatus.TOO_MANY_REQUESTS ? "Rate Limit Exceeded" : "Authentication Temporarily Unavailable"), detail, request);
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (retryAfter != null) {
            body.setProperty("retryAfterSeconds", retryAfter);
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }
        return response.body(body);
    }

    private record FieldValidationError(String field, String message, Object rejectedValue) { }
}
