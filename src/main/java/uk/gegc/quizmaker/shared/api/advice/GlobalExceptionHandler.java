package uk.gegc.quizmaker.shared.api.advice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import uk.gegc.quizmaker.features.billing.domain.exception.*;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.documentProcess.domain.NormalizationFailedException;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;
import uk.gegc.quizmaker.shared.exception.*;

import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.NOT_FOUND,
                ErrorTypes.RESOURCE_NOT_FOUND,
                "Resource Not Found",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleDocumentNotFound(DocumentNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.NOT_FOUND,
                ErrorTypes.DOCUMENT_NOT_FOUND,
                "Document Not Found",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler({ValidationException.class, ValidationErrorException.class})
    public ResponseEntity<ProblemDetail> handleValidation(RuntimeException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.VALIDATION_FAILED,
                "Validation Failed",
                ex.getMessage(),
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(UnsupportedQuestionTypeException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedQuestionType(UnsupportedQuestionTypeException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.UNSUPPORTED_QUESTION_TYPE,
                "Unsupported Question Type",
                ex.getMessage(),
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedFileType(UnsupportedFileTypeException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.UNSUPPORTED_FILE_TYPE,
                "Unsupported File Type",
                ex.getMessage(),
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler({ApiError.class, QuizGenerationException.class})
    public ResponseEntity<ProblemDetail> handleQuizGeneration(RuntimeException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.QUIZ_GENERATION_FAILED,
                "Quiz Generation Error",
                ex.getMessage(),
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(UnsupportedFormatException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedFormat(UnsupportedFormatException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorTypes.UNSUPPORTED_FORMAT,
                "Unsupported Format",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
    }

    @ExceptionHandler(ConversionFailedException.class)
    public ResponseEntity<ProblemDetail> handleConversionFailed(ConversionFailedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorTypes.CONVERSION_FAILED,
                "Conversion Failed",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(NormalizationFailedException.class)
    public ResponseEntity<ProblemDetail> handleNormalizationFailed(NormalizationFailedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorTypes.NORMALIZATION_FAILED,
                "Normalization Failed",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedOperation(UnsupportedOperationException ex, HttpServletRequest request) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Operation not supported";
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.UNSUPPORTED_OPERATION,
                "Unsupported Operation",
                message,
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.INVALID_ARGUMENT,
                "Invalid Argument",
                ex.getMessage(),
                request
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorTypes.ILLEGAL_STATE,
                "Illegal State",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(AttemptNotCompletedException.class)
    public ResponseEntity<ProblemDetail> handleAttemptNotCompleted(AttemptNotCompletedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.ATTEMPT_NOT_COMPLETED,
                "Attempt Not Completed",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
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

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ProblemDetail> handleAiServiceException(AiServiceException ex, HttpServletRequest request) {
        boolean unavailable = ex.getCause() instanceof RestClientException;
        HttpStatus status = unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetailBuilder.create(
                status,
                unavailable ? ErrorTypes.AI_SERVICE_UNAVAILABLE : ErrorTypes.AI_SERVICE_ERROR,
                unavailable ? "AI Service Unavailable" : "AI Service Error",
                unavailable ? "The AI service is currently unavailable. Please try again later." : "An unexpected error occurred with the AI service.",
                request
        );
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        logger.error("RuntimeException occurred: {}", ex.getMessage(), ex);
        if ("Access denied".equalsIgnoreCase(ex.getMessage())) {
            ProblemDetail problem = ProblemDetailBuilder.create(
                    HttpStatus.FORBIDDEN,
                    ErrorTypes.ACCESS_DENIED,
                    "Access Denied",
                    ex.getMessage(),
                    request
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
        }
        if ("Chunk not found".equalsIgnoreCase(ex.getMessage())) {
            ProblemDetail problem = ProblemDetailBuilder.create(
                    HttpStatus.NOT_FOUND,
                    ErrorTypes.RESOURCE_NOT_FOUND,
                    "Not Found",
                    ex.getMessage(),
                    request
            );
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred",
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<ProblemDetail> handleDocumentProcessing(DocumentProcessingException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.DOCUMENT_PROCESSING_FAILED,
                "Document Processing Error",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ProblemDetail> handleDocumentStorage(DocumentStorageException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.DOCUMENT_STORAGE_FAILED,
                "Document Storage Error",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        
        // Map specific status codes to specific error types
        java.net.URI errorType = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED;
            case FORBIDDEN -> ErrorTypes.ACCESS_DENIED;
            case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case CONFLICT -> ErrorTypes.DATA_CONFLICT;
            case UNPROCESSABLE_ENTITY -> ErrorTypes.ILLEGAL_STATE;
            case BAD_REQUEST -> ErrorTypes.VALIDATION_FAILED;
            default -> ErrorTypes.GENERIC_ERROR;
        };
        
        ProblemDetail problem = ProblemDetailBuilder.create(
                status,
                errorType,
                status.getReasonPhrase(),
                reason,
                request
        );
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.OPTIMISTIC_LOCK_CONFLICT,
                "Conflict",
                "Quiz has been modified by another user. Please refresh and try again.",
                request
        );
        problem.setProperty("errorCode", "QUIZ_VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InsufficientTokensException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientTokens(InsufficientTokensException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.INSUFFICIENT_TOKENS,
                "Insufficient Tokens",
                ex.getMessage(),
                request
        );
        problem.setProperty("errorCode", "INSUFFICIENT_TOKENS");
        problem.setProperty("estimatedTokens", ex.getEstimatedTokens());
        problem.setProperty("availableTokens", ex.getAvailableTokens());
        problem.setProperty("shortfall", ex.getShortfall());
        problem.setProperty("reservationTtl", ex.getReservationTtl());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InsufficientAvailableTokensException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientAvailableTokens(InsufficientAvailableTokensException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.INSUFFICIENT_AVAILABLE_TOKENS,
                "Insufficient Available Tokens",
                ex.getMessage(),
                request
        );
        problem.setProperty("errorCode", "INSUFFICIENT_AVAILABLE_TOKENS");
        problem.setProperty("requestedTokens", ex.getRequestedTokens());
        problem.setProperty("availableTokens", ex.getAvailableTokens());
        problem.setProperty("shortfall", ex.getShortfall());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ReservationNotActiveException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotActive(ReservationNotActiveException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.RESERVATION_NOT_ACTIVE,
                "Reservation Not Active",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(CommitExceedsReservedException.class)
    public ResponseEntity<ProblemDetail> handleCommitExceedsReserved(CommitExceedsReservedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.COMMIT_EXCEEDS_RESERVED,
                "Commit Exceeds Reserved",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PackNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePackNotFound(PackNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.NOT_FOUND,
                ErrorTypes.PACK_NOT_FOUND,
                "Pack Not Found",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(StripeWebhookInvalidSignatureException.class)
    public ResponseEntity<ProblemDetail> handleStripeInvalidSignature(StripeWebhookInvalidSignatureException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNAUTHORIZED,
                ErrorTypes.STRIPE_WEBHOOK_INVALID_SIGNATURE,
                "Stripe Webhook Invalid Signature",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.IDEMPOTENCY_CONFLICT,
                "Idempotency Conflict",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InvalidJobStateForCommitException.class)
    public ResponseEntity<ProblemDetail> handleInvalidJobStateForCommit(InvalidJobStateForCommitException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.INVALID_JOB_STATE_FOR_COMMIT,
                "Invalid Job State for Commit",
                ex.getMessage(),
                request
        );
        problem.setProperty("jobId", ex.getJobId());
        problem.setProperty("currentState", ex.getCurrentState().name());
        problem.setProperty("expectedState", "RESERVED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String detail = "Database error: " + ex.getMostSpecificCause().getMessage();
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.CONFLICT,
                ErrorTypes.DATA_CONFLICT,
                "Data Conflict",
                detail,
                request
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ProblemDetail> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.UNAUTHORIZED,
                ErrorTypes.UNAUTHORIZED,
                "Unauthorized",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class, ForbiddenException.class, UserNotAuthorizedException.class})
    public ResponseEntity<ProblemDetail> handleAccessDenied(Exception ex, HttpServletRequest request) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "You do not have permission to access this resource";
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.FORBIDDEN,
                ErrorTypes.ACCESS_DENIED,
                "Access Denied",
                detail,
                request
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(DocumentAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleDocumentAccessDenied(DocumentAccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.FORBIDDEN,
                ErrorTypes.DOCUMENT_ACCESS_DENIED,
                "Document Access Denied",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(ShareLinkAlreadyUsedException.class)
    public ResponseEntity<ProblemDetail> handleShareLinkAlreadyUsed(ShareLinkAlreadyUsedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.GONE,
                ErrorTypes.SHARE_LINK_ALREADY_USED,
                "Share Link Already Used",
                ex.getMessage(),
                request
        );
        return ResponseEntity.status(HttpStatus.GONE).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.CONSTRAINT_VIOLATION,
                "Constraint Violation",
                "One or more validation constraints were violated",
                request
        );
        List<ViolationDetail> violations = ex.getConstraintViolations().stream()
                .map(this::toViolationDetail)
                .collect(Collectors.toList());
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    private ViolationDetail toViolationDetail(ConstraintViolation<?> violation) {
        return new ViolationDetail(
                violation.getPropertyPath().toString(),
                violation.getMessage(),
                violation.getInvalidValue()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String param = ex.getName();
        Class<?> type = ex.getRequiredType();
        String requiredType = type != null ? type.getSimpleName() : "unknown";
        String msg = "Invalid value for parameter '" + param + "'. Expected type: " + requiredType + ".";
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.TYPE_MISMATCH,
                "Type Mismatch",
                msg,
                request
        );
        problem.setProperty("parameter", param);
        problem.setProperty("expectedType", requiredType);
        problem.setProperty("providedValue", ex.getValue());
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request
    ) {
        String msg = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.MALFORMED_JSON,
                "Malformed JSON",
                "Request body is malformed or cannot be read",
                request
        );
        problem.setProperty("parseError", msg);
        return new ResponseEntity<>(problem, headers, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request
    ) {
        List<FieldValidationError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage(), error.getRejectedValue()))
                .toList();
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.VALIDATION_FAILED,
                "Validation Failed",
                "Validation failed for one or more fields",
                request
        );
        problem.setProperty("fieldErrors", fieldErrors);
        return new ResponseEntity<>(problem, headers, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAllOthers(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred",
                request
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private record ViolationDetail(String field, String message, Object invalidValue) {
    }

    private record FieldValidationError(String field, String message, Object rejectedValue) {
    }
}
