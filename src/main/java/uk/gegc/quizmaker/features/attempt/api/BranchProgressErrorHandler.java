package uk.gegc.quizmaker.features.attempt.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;

@RestControllerAdvice(assignableTypes = BranchProgressController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BranchProgressErrorHandler {
    @ExceptionHandler(BranchingException.class)
    public ResponseEntity<ProblemDetail> rejected(BranchingException failure, HttpServletRequest request) {
        return problem(failure.reason(), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            ResponseAcceptanceJsonConfig.InvalidRequest.class})
    public ResponseEntity<ProblemDetail> malformed(HttpServletRequest request) {
        return problem(BranchingException.Reason.INVALID, request);
    }

    private static ResponseEntity<ProblemDetail> problem(BranchingException.Reason reason, HttpServletRequest request) {
        var status = switch (reason) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case DENIED -> HttpStatus.NOT_FOUND;
            case STALE, CONFLICT, INACTIVE, ANSWER_REQUIRED -> HttpStatus.CONFLICT;
            case UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CORRUPT -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UNAVAILABLE, RETRYABLE, UNKNOWN_OUTCOME -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var type = switch (status) {
            case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> ErrorTypes.INVALID_ARGUMENT;
            case CONFLICT -> ErrorTypes.ILLEGAL_STATE;
            default -> ErrorTypes.PROCESSING_FAILED;
        };
        var detail = ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), "Branch progress could not be advanced", request);
        detail.setProperty("code", reason == BranchingException.Reason.DENIED ? "BRANCH_NOT_FOUND" : "BRANCH_" + reason.name());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }
}
