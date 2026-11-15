package uk.gegc.quizmaker.features.attempt.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.*;
import uk.gegc.quizmaker.shared.api.problem.*;

@RestControllerAdvice(assignableTypes = ResponseBatchController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResponseBatchErrorHandler extends ResponseAcceptanceErrorHandler {
    @ExceptionHandler(ResponseBatchException.class)
    public ResponseEntity<ProblemDetail> batch(ResponseBatchException failure, HttpServletRequest request) {
        var status = switch (failure.reason()) {
            case MALFORMED -> HttpStatus.BAD_REQUEST; case LIMIT -> HttpStatus.PAYLOAD_TOO_LARGE;
            case INVALID_ITEMS -> HttpStatus.UNPROCESSABLE_ENTITY; case CONFLICT -> HttpStatus.CONFLICT;
        };
        var body = ProblemDetailBuilder.create(status, status == HttpStatus.CONFLICT ? ErrorTypes.ILLEGAL_STATE : ErrorTypes.INVALID_ARGUMENT, status.getReasonPhrase(),
                "Response batch could not be accepted", request);
        body.setProperty("code", "RESPONSE_BATCH_" + failure.reason());
        if (!failure.errors().isEmpty()) body.setProperty("errors", failure.errors().stream().map(e ->
                new uk.gegc.quizmaker.features.attempt.api.dto.ResponseBatchProblem.ItemError(e.index(), e.code().name())).toList());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
