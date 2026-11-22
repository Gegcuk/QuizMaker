package uk.gegc.quizmaker.features.quiz.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;

import java.net.URI;

@RestControllerAdvice(assignableTypes = QuizRevisionPublicationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuizRevisionPublicationErrorHandler {
    @ExceptionHandler(PublicationException.class)
    public ResponseEntity<ProblemDetail> publicationFailure(PublicationException failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case INVALID_INPUT -> invalidInput(request);
            case UNAUTHENTICATED -> problem(HttpStatus.UNAUTHORIZED, ErrorTypes.UNAUTHORIZED,
                    "Authentication is required to publish a quiz.", "UNAUTHENTICATED", request);
            case UNAVAILABLE -> problem(HttpStatus.NOT_FOUND, ErrorTypes.RESOURCE_NOT_FOUND,
                    "Quiz publication is unavailable.", "UNAVAILABLE", request);
            case INVALID_CONTENT -> problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.INVALID_ARGUMENT,
                    "The question order or draft content cannot be published.", "INVALID_CONTENT", request);
            case COMMAND_CONFLICT -> problem(HttpStatus.CONFLICT, ErrorTypes.IDEMPOTENCY_CONFLICT,
                    "The command ID was already used with different inputs.", "COMMAND_CONFLICT", request);
            case STALE_PUBLICATION -> problem(HttpStatus.CONFLICT, ErrorTypes.OPTIMISTIC_LOCK_CONFLICT,
                    "The expected publication version does not match.", "STALE_PUBLICATION", request);
            case IMAGE_UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.PROCESSING_FAILED,
                    "Required retained media is unavailable.", "IMAGE_UNAVAILABLE", request);
            case PERSISTENCE_UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.PROCESSING_FAILED,
                    "Publication could not be saved. Retry the same command.", "PERSISTENCE_UNAVAILABLE", request);
            case UNKNOWN_OUTCOME -> problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.PROCESSING_FAILED,
                    "Publication outcome is unknown. Retry with the same command ID and identical inputs.", "UNKNOWN_OUTCOME", request);
            case TRANSACTION_NOT_ALLOWED, UNSUPPORTED_VERSION, INVALID_RETAINED_REVISION ->
                    problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorTypes.PROCESSING_FAILED,
                            "Quiz publication could not be processed.", "INTERNAL_ERROR", request);
        };
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalidInput(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ErrorTypes.INVALID_ARGUMENT,
                "The publication request is invalid.", "INVALID_INPUT", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type, String detail, String code,
                                                  HttpServletRequest request) {
        var body = ProblemDetailBuilder.create(status, type, status.getReasonPhrase(), detail, request);
        body.setProperty("code", "QUIZ_PUBLICATION_" + code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
