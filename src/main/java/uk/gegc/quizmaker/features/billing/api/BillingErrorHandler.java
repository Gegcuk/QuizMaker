package uk.gegc.quizmaker.features.billing.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientAvailableTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.exception.LargePayloadSecurityException;
import uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import com.stripe.exception.StripeException;

import java.util.HashMap;
import java.util.Map;

/**
 * Global error handler for billing API endpoints.
 * Maps domain exceptions to RFC 7807 Problem Detail responses.
 */
@Slf4j
@RestControllerAdvice(basePackages = "uk.gegc.quizmaker.features.billing.api")
public class BillingErrorHandler {

    @ExceptionHandler(InvalidCheckoutSessionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCheckoutSession(InvalidCheckoutSessionException ex, HttpServletRequest request) {
        log.warn("Invalid checkout session: {}", ex.getMessage());

        if (isWebhookRequest(request)) {
            log.error("Webhook processing failed due to invalid checkout session: {}", ex.getMessage());
            ProblemDetail problem = ProblemDetailBuilder.create(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorTypes.WEBHOOK_PROCESSING_ERROR,
                    "Webhook Processing Error",
                    ex.getMessage(),
                    request
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }

        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.NOT_FOUND,
                ErrorTypes.INVALID_CHECKOUT_SESSION,
                "Invalid Checkout Session",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    private boolean isWebhookRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/stripe/webhook");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.IDEMPOTENCY_CONFLICT,
                "Idempotency Conflict",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InsufficientTokensException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientTokens(InsufficientTokensException ex, HttpServletRequest request) {
        log.warn("Insufficient tokens: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.INSUFFICIENT_TOKENS,
                "Insufficient Tokens",
                ex.getMessage(),
                request
        );
        problem.setProperty("estimatedTokens", ex.getEstimatedTokens());
        problem.setProperty("availableTokens", ex.getAvailableTokens());
        problem.setProperty("shortfall", ex.getShortfall());
        problem.setProperty("reservationTtl", ex.getReservationTtl());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InsufficientAvailableTokensException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientAvailableTokens(InsufficientAvailableTokensException ex, HttpServletRequest request) {
        log.warn("Insufficient available tokens: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.INSUFFICIENT_AVAILABLE_TOKENS,
                "Insufficient Available Tokens",
                ex.getMessage(),
                request
        );
        problem.setProperty("requestedTokens", ex.getRequestedTokens());
        problem.setProperty("availableTokens", ex.getAvailableTokens());
        problem.setProperty("shortfall", ex.getShortfall());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ReservationNotActiveException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotActive(ReservationNotActiveException ex, HttpServletRequest request) {
        log.warn("Reservation not active: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
            HttpStatus.CONFLICT,
            ErrorTypes.RESERVATION_NOT_ACTIVE,
            "Reservation Not Active",
            ex.getMessage(),
            request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(StripeWebhookInvalidSignatureException.class)
    public ResponseEntity<ProblemDetail> handleInvalidWebhookSignature(StripeWebhookInvalidSignatureException ex, HttpServletRequest request) {
        log.warn("Invalid webhook signature: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNAUTHORIZED,
                ErrorTypes.STRIPE_WEBHOOK_INVALID_SIGNATURE,
                "Stripe Webhook Invalid Signature",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("Validation errors: {}", ex.getMessage());
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.VALIDATION_FAILED,
                "Validation Failed",
                "Validation failed for one or more fields",
                request
        );
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("Type mismatch error: {}", ex.getMessage());
        String param = ex.getName();
        Class<?> type = ex.getRequiredType();
        String requiredType = type != null ? type.getSimpleName() : "unknown";
        String detail = "Invalid value for parameter '" + param + "'. Expected type: " + requiredType + ".";
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.TYPE_MISMATCH,
                "Type Mismatch Error",
                detail,
                request
        );
        problem.setProperty("parameter", param);
        problem.setProperty("expectedType", requiredType);
        problem.setProperty("providedValue", ex.getValue());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.INVALID_ARGUMENT,
                "Invalid Argument",
                ex.getMessage(),
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        log.warn("Forbidden access: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.FORBIDDEN,
                ErrorTypes.ACCESS_DENIED,
                "Forbidden",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(LargePayloadSecurityException.class)
    public ResponseEntity<ProblemDetail> handleLargePayloadSecurity(LargePayloadSecurityException ex, HttpServletRequest request) {
        log.error("Large payload security violation: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.BILLING_SECURITY_ERROR,
                "Security Error",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(StripeException.class)
    public ResponseEntity<ProblemDetail> handleStripeException(StripeException ex, HttpServletRequest request) {
        log.error("Stripe API error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.STRIPE_ERROR,
                "Payment Processing Error",
                "Payment processing error",
                request
        );
        if (ex.getCode() != null) {
            problem.setProperty("stripeCode", ex.getCode());
        }
        if (ex.getRequestId() != null) {
            problem.setProperty("stripeRequestId", ex.getRequestId());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorTypes.RATE_LIMIT_EXCEEDED,
                "Rate Limit Exceeded",
                ex.getMessage(),
                request
        );
        problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        boolean configurationIssue = ex.getMessage() != null && (
                ex.getMessage().contains("configuration") ||
                ex.getMessage().contains("config") ||
                ex.getMessage().contains("misconfigured") ||
                ex.getMessage().contains("missing configuration"));

        if (configurationIssue) {
            log.error("Configuration error in billing API: {}", ex.getMessage(), ex);
            ProblemDetail problem = ProblemDetailBuilder.create(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorTypes.BILLING_CONFIGURATION_ERROR,
                    "Service Configuration Error",
                    ex.getMessage(),
                    request
            );
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
        }

        log.error("Illegal state: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.BILLING_INTERNAL_ERROR,
                "Internal Error",
                "An internal error occurred",
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Invalid request body: {}", ex.getMessage());
        String detail = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.BILLING_INVALID_REQUEST_BODY,
                "Invalid Request Body",
                "Invalid request body",
                request
        );
        problem.setProperty("parseError", detail);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error in billing API: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.BILLING_INTERNAL_ERROR,
                "Internal Error",
                "An unexpected error occurred",
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
