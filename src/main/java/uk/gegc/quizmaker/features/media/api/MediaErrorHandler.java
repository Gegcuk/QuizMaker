package uk.gegc.quizmaker.features.media.api;

import org.springframework.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MediaErrorHandler {
    @ExceptionHandler(SavedImageException.class)
    public ResponseEntity<ProblemDetail> savedImage(SavedImageException failure) {
        var status = failure.reason() == SavedImageException.Reason.CONFLICT ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        var problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
