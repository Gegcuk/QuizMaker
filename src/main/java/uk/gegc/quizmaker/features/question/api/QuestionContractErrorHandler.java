package uk.gegc.quizmaker.features.question.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.application.QuestionContentUnavailableException;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.List;

@RestControllerAdvice(assignableTypes = {QuestionController.class,
        uk.gegc.quizmaker.features.attempt.api.AttemptController.class,
        uk.gegc.quizmaker.features.quiz.api.ShareLinkController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuestionContractErrorHandler {
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidFields(
            org.springframework.web.bind.MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION_FAILED,
                "Validation Failed", "Validation failed for one or more fields", request);
        problem.setInstance(operationInstance(request));
        // Preserve field diagnostics, never serialize rejected JSON or unbounded batch errors.
        var errors = ex.getBindingResult().getFieldErrors();
        List<FieldError> diagnostics = errors.stream().limit(16)
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage(), null)).toList();
        problem.setProperty("fieldErrors", diagnostics);
        if (errors.size() > diagnostics.size()) problem.setProperty("errorsTruncated", true);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Jackson diagnostics can contain raw client values or arbitrarily long enum names.
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof QuestionContractException contract) return invalid(contract, request);
        }
        ProblemDetail problem = ProblemDetailBuilder.create(HttpStatus.BAD_REQUEST, ErrorTypes.MALFORMED_JSON,
                "Malformed JSON", "Request body is malformed or cannot be read", request);
        problem.setInstance(operationInstance(request));
        problem.setProperty("code", "MALFORMED_QUESTION_REQUEST");
        problem.setProperty("field", "body");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> invalidParameter(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "unknown" : ex.getRequiredType().getSimpleName();
        ProblemDetail problem = ProblemDetailBuilder.create(HttpStatus.BAD_REQUEST, ErrorTypes.TYPE_MISMATCH,
                "Type Mismatch", "Invalid value for parameter '" + ex.getName() + "'. Expected type: " + expected + ".", request);
        problem.setInstance(operationInstance(request));
        problem.setProperty("parameter", ex.getName());
        problem.setProperty("expectedType", expected);
        return ResponseEntity.badRequest().body(problem);
    }
    @ExceptionHandler(QuestionContractException.class)
    public ResponseEntity<ProblemDetail> invalid(QuestionContractException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailBuilder.create(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION_FAILED,
                "Validation Failed", ex.getMessage(), request);
        problem.setInstance(operationInstance(request));
        problem.setProperty("code", ex.getCode());
        problem.setProperty("field", ex.getField());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(QuestionContentUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
        ProblemDetail marker = QuestionContracts.unavailableProblem();
        ProblemDetail problem = ProblemDetailBuilder.create(HttpStatus.CONFLICT, marker.getType(),
                marker.getTitle(), marker.getDetail(), request);
        problem.setInstance(operationInstance(request));
        return ResponseEntity.status(409).body(problem);
    }

    private URI operationInstance(HttpServletRequest request) {
        // Spring otherwise fills an absent instance with the raw, unbounded request path.
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern == null ? "/api/v1" : pattern.toString();
        return URI.create(path.replace("{", "%7B").replace("}", "%7D"));
    }

    private record FieldError(String field, String message, Object rejectedValue) { }
}
