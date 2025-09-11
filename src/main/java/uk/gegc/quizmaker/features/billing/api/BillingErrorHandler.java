package uk.gegc.quizmaker.features.billing.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Global error handler for billing API endpoints.
 * Maps domain exceptions to RFC-9457 ProblemDetail responses.
 */
@Slf4j
@RestControllerAdvice(basePackages = "uk.gegc.quizmaker.features.billing.api")
public class BillingErrorHandler {

    @ExceptionHandler(InvalidCheckoutSessionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCheckoutSession(InvalidCheckoutSessionException ex) {
        log.warn("Invalid checkout session: {}", ex.getMessage());
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/invalid-checkout-session"));
        problemDetail.setTitle("Invalid Checkout Session");
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/idempotency-conflict"));
        problemDetail.setTitle("Idempotency Conflict");
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    @ExceptionHandler(InsufficientTokensException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientTokens(InsufficientTokensException ex) {
        log.warn("Insufficient tokens: {}", ex.getMessage());
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/insufficient-tokens"));
        problemDetail.setTitle("Insufficient Tokens");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(ReservationNotActiveException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotActive(ReservationNotActiveException ex) {
        log.warn("Reservation not active: {}", ex.getMessage());
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/reservation-not-active"));
        problemDetail.setTitle("Reservation Not Active");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(StripeWebhookInvalidSignatureException.class)
    public ResponseEntity<ProblemDetail> handleInvalidWebhookSignature(StripeWebhookInvalidSignatureException ex) {
        log.warn("Invalid webhook signature: {}", ex.getMessage());
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/invalid-webhook-signature"));
        problemDetail.setTitle("Invalid Webhook Signature");
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(MethodArgumentNotValidException ex) {
        log.warn("Validation errors: {}", ex.getMessage());
        
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Validation failed");
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/validation-error"));
        problemDetail.setTitle("Validation Error");
        problemDetail.setProperty("errors", errors);
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/invalid-argument"));
        problemDetail.setTitle("Invalid Argument");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage(), ex);
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred");
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/internal-error"));
        problemDetail.setTitle("Internal Error");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        log.error("Unexpected error in billing API: {}", ex.getMessage(), ex);
        
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problemDetail.setType(URI.create("https://api.quizmaker.com/problems/internal-error"));
        problemDetail.setTitle("Internal Error");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}
