package uk.gegc.quizmaker.features.attempt.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;

/** Only the new typed read failure; legacy errors and unrelated controller methods retain their advice. */
@RestControllerAdvice(assignableTypes = AttemptController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ParticipantAttemptViewErrorHandler {
    @ExceptionHandler(AttemptDeliveryException.class)
    public ResponseEntity<ProblemDetail> handle(AttemptDeliveryException failure, HttpServletRequest request) {
        return new AttemptDeliveryErrorHandler().handle(failure, request);
    }
}
