package uk.gegc.quizmaker.features.attempt.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptSubmitProblem;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.shared.api.problem.*;

@RestControllerAdvice(assignableTypes = AttemptSubmissionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AttemptSubmissionErrorHandler {
    @ExceptionHandler(AttemptSubmissionException.class)
    public ResponseEntity<AttemptSubmitProblem> handle(AttemptSubmissionException failure, HttpServletRequest request) {
        var status = switch (failure.reason()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED; case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED, INCOMPLETE, CONFIRMATION_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT, STALE, INACTIVE, IN_PROGRESS, EXPIRED -> HttpStatus.CONFLICT;
            case CORRUPT -> HttpStatus.INTERNAL_SERVER_ERROR;
            case RETRYABLE, UNKNOWN_OUTCOME, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, "SUBMISSION_" + failure.reason().name(), failure.missing(), request);
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class, ReceiptException.class,
            AttemptSubmissionJsonConfig.InvalidRequest.class})
    public ResponseEntity<AttemptSubmitProblem> malformed(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SUBMISSION_REQUEST", List.of(), request);
    }
    private static ResponseEntity<AttemptSubmitProblem> response(HttpStatus status, String code, List<UUID> missing, HttpServletRequest request) {
        var type = switch (status) {
            case UNAUTHORIZED -> ErrorTypes.UNAUTHORIZED; case NOT_FOUND -> ErrorTypes.RESOURCE_NOT_FOUND;
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> ErrorTypes.INVALID_ARGUMENT; case CONFLICT -> ErrorTypes.ILLEGAL_STATE;
            default -> ErrorTypes.PROCESSING_FAILED;
        };
        var detail = ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), "Attempt could not be submitted", request);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new AttemptSubmitProblem(detail, code, missing));
    }
}
