package uk.gegc.quizmaker.shared.api.advice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import uk.gegc.quizmaker.shared.exception.*;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import uk.gegc.quizmaker.features.documentProcess.domain.NormalizationFailedException;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;

import java.time.LocalDateTime;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler({ResourceNotFoundException.class, DocumentNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(RuntimeException exception) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                List.of(exception.getMessage())
        );
    }

    @ExceptionHandler({
            ValidationException.class,
            UnsupportedQuestionTypeException.class,
            UnsupportedFileTypeException.class,
            ApiError.class,
            QuizGenerationException.class,
            ValidationErrorException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(RuntimeException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(UnsupportedFormatException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ErrorResponse handleUnsupportedFormat(UnsupportedFormatException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "Unsupported Format",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler({ConversionFailedException.class, NormalizationFailedException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleProcessingFailed(RuntimeException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Processing Failed",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnsupportedOperation(UnsupportedOperationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Operation not supported";
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                List.of(msg)
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException exception) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad request",
                List.of(exception.getMessage())
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleIllegalState(IllegalStateException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Processing Failed",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest req) {
        var body = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                List.of(ex.getMessage())
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }



    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException ex) {
        HttpStatus status = ex.getCause() instanceof org.springframework.web.client.RestClientException
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR;

        return ResponseEntity.status(status).body(
                new ErrorResponse(
                        LocalDateTime.now(),
                        status.value(),
                        status == HttpStatus.SERVICE_UNAVAILABLE ? "AI Service Unavailable" : "AI Service Error",
                        List.of(status == HttpStatus.SERVICE_UNAVAILABLE
                                ? "The AI service is currently unavailable. Please try again later."
                                : "An unexpected error occurred with the AI service.")
                )
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex) {
        // Handle specific authorization cases
        if ("Access denied".equals(ex.getMessage())) {
            ErrorResponse errorResponse = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.FORBIDDEN.value(),
                    "Access Denied",
                    List.of(ex.getMessage())
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
        }
        // Handle specific not found cases
        if ("Chunk not found".equals(ex.getMessage())) {
            ErrorResponse errorResponse = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.NOT_FOUND.value(),
                    "Not Found",
                    List.of(ex.getMessage())
            );
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
        // For other runtime exceptions, return internal server error
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                List.of("An unexpected error occurred")
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    @ExceptionHandler(DocumentProcessingException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleDocumentProcessing(DocumentProcessingException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Document Processing Error",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(DocumentStorageException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleDocumentStorage(DocumentStorageException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Document Storage Error",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(
                new ErrorResponse(
                        LocalDateTime.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        List.of(ex.getReason() != null ? ex.getReason() : ex.getMessage())
                )
        );
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(org.springframework.dao.OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Quiz has been modified by another user. Please refresh and try again.");
        problem.setTitle("Conflict");
        problem.setProperty("errorCode", "QUIZ_VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    // ===================== Billing-specific errors (ProblemDetail) =====================
    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientTokens(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Insufficient tokens");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/insufficient-tokens"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotActive(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Reservation not active");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/reservation-not-active"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.CommitExceedsReservedException.class)
    public ResponseEntity<ProblemDetail> handleCommitExceedsReserved(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Commit exceeds reserved");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/commit-exceeds-reserved"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.PackNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePackNotFound(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Pack not found");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/pack-not-found"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCheckoutSession(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Invalid checkout session");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/invalid-checkout-session"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException.class)
    public ResponseEntity<ProblemDetail> handleStripeInvalidSignature(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Stripe webhook invalid signature");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/stripe-webhook-invalid-signature"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(RuntimeException ex, HttpServletRequest r) {
        var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Idempotency conflict");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(r.getRequestURI()));
        pd.setType(URI.create("https://example.com/problems/idempotency-conflict"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDataIntegrity(DataIntegrityViolationException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                List.of("Database error: " + ex.getMostSpecificCause().getMessage())
        );
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauthorized(UnauthorizedException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class, ForbiddenException.class, DocumentAccessDeniedException.class, UserNotAuthorizedException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDenied(Exception ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.FORBIDDEN.value(),
                "Access Denied",
                List.of(ex.getMessage() != null ? ex.getMessage() : "You do not have permission to access this resource")
        );
    }

    @ExceptionHandler(ShareLinkAlreadyUsedException.class)
    @ResponseStatus(HttpStatus.GONE)
    public ErrorResponse handleShareLinkAlreadyUsed(ShareLinkAlreadyUsedException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.GONE.value(),
                "Gone",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage) // or include propertyPath if you prefer
                .collect(Collectors.toList());

        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                details
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String param = ex.getName();
        Class<?> type = ex.getRequiredType();
        String requiredType = (type != null ? type.getSimpleName() : "unknown");
        String msg = "Invalid value for parameter '" + param + "'. Expected type: " + requiredType + ".";
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                List.of(msg)
        );
    }

    @Override
    protected org.springframework.http.ResponseEntity<Object> handleHttpMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request
    ) {
        String msg = ex.getMostSpecificCause().getMessage();
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Malformed JSON",
                List.of(msg)
        );
        return new org.springframework.http.ResponseEntity<>(body, headers, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request
    ) {
        List<String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();

        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                fieldErrors
        );
        return new ResponseEntity<>(body, headers, status);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleAllOthers(Exception ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                List.of("An unexpected error occurred")
        );
    }

    public record ErrorResponse(
            LocalDateTime timestamp,
            int status,
            String error,
            List<String> details
    ) {
    }
}
