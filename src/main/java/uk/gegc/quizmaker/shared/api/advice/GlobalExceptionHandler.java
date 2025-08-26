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

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler({ResourceNotFoundException.class, DocumentNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException exception, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        pd.setTitle("Resource Not Found");
        pd.setType(java.net.URI.create("urn:problem-type:resource-not-found"));
        pd.setProperty("code", "RESOURCE_NOT_FOUND");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler({
            ValidationException.class,
            UnsupportedQuestionTypeException.class,
            UnsupportedFileTypeException.class,
            ApiError.class,
            QuizGenerationException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        pd.setType(java.net.URI.create("urn:problem-type:validation-error"));
        pd.setProperty("code", "VALIDATION_ERROR");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
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
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        pd.setTitle("Bad Request");
        pd.setType(java.net.URI.create("urn:problem-type:validation-error"));
        pd.setProperty("code", "VALIDATION_ERROR");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleIllegalState(IllegalStateException ex) {
        return new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        pd.setTitle("Rate Limit Exceeded");
        pd.setType(java.net.URI.create("urn:problem-type:rate-limit"));
        pd.setProperty("code", "RATE_LIMIT_EXCEEDED");
        pd.setProperty("retryAfter", ex.getRetryAfterSeconds());
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(pd);
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
                    "Resource Not Found",
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
    public ResponseEntity<ProblemDetail> handleDocumentProcessing(DocumentProcessingException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Document Processing Error");
        pd.setType(java.net.URI.create("urn:problem-type:document-processing"));
        pd.setProperty("code", "DOCUMENT_PROCESSING_ERROR");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
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
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getReason() != null ? ex.getReason() : ex.getMessage());
        pd.setTitle(status.getReasonPhrase());
        pd.setType(java.net.URI.create("urn:problem-type:validation-error"));
        pd.setProperty("code", "VALIDATION_ERROR");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.status(status).body(pd);
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(org.springframework.dao.OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Quiz has been modified by another user. Please refresh and try again.");
        problem.setTitle("Conflict");
        problem.setProperty("errorCode", "QUIZ_VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
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
    public ResponseEntity<ProblemDetail> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setTitle("Unauthorized");
        pd.setType(java.net.URI.create("urn:problem-type:validation-error"));
        pd.setProperty("code", "VALIDATION_ERROR");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
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
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
        pd.setTitle("Bad Request");
        pd.setType(java.net.URI.create("urn:problem-type:validation-error"));
        pd.setProperty("code", "VALIDATION_ERROR");
        
        return new org.springframework.http.ResponseEntity<>(pd, headers, HttpStatus.BAD_REQUEST);
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

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setTitle("Validation Error");
        pd.setType(java.net.URI.create("urn:problem-type:validation-error"));
        pd.setProperty("code", "VALIDATION_ERROR");
        pd.setProperty("fieldErrors", fieldErrors);
        
        return new ResponseEntity<>(pd, headers, status);
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