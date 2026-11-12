package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService;
import uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException;

@RestController
@RequestMapping("/api/v1/attempts")
@Tag(name = "Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.response-batch-enabled", havingValue = "true")
public class ResponseBatchController {
    private final ResponseBatchAcceptanceService responses;
    private final ObjectMapper json;
    public ResponseBatchController(ResponseBatchAcceptanceService responses, ObjectMapper json) { this.responses = responses; this.json = json; }
    @PostMapping(value = "/{attemptId}/responses/batch", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "acceptAttemptResponseBatch", summary = "Accept an atomic response batch",
            description = "Online ALL_AT_ONCE target attempts only. Requires authentication, ATTEMPT_CREATE, the bound participant and current quiz ownership/full editing authority. " +
                    "Expected state version and exact Idempotency-Key follow single-response acceptance. Request order is part of identity; result/errors use saved form order. " +
                    "At most 500 items, 5 MiB UTF-8 JSON and 32 KiB per text, subject to stricter family limits (100 elements, 10000 characters). " +
                    "Duplicates, unknown items or any invalid answer reject the entire batch; accepted answers cannot be replaced. " +
                    "No completion, grading, feedback, automatic retry or offline mutation. Retry an unknown outcome with exactly the same command. " +
                    "Identical replay rechecks current authority and returns original IDs/version. Disabled by default; legacy /answers/batch remains unchanged.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "All responses committed or identical replay", content = @Content(schema = @Schema(implementation = ResponseBatchAcknowledgement.class),
            examples = @ExampleObject(value = """
            {"attemptId":"00000000-0000-0000-0000-000000000001","responses":[{"formItemId":"00000000-0000-0000-0000-000000000002","acceptedResponseId":"00000000-0000-0000-0000-000000000003"}],"stateVersion":2,"replayed":false}
            """)))
    @ApiResponse(responseCode = "400", description = "Malformed JSON/header/request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "404", description = "Absent, foreign or inaccessible attempt", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "409", description = "Lifecycle/version/idempotency/accepted-answer conflict", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "413", description = "Body/item-count limit", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "422", description = "Invalid items; errors contain request indexes in form order", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "500", description = "Corrupt saved evidence", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    @ApiResponse(responseCode = "503", description = "Storage failure, retryable conflict or unknown commit", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ResponseBatchProblem.class)))
    public ResponseEntity<ResponseBatchAcknowledgement> accept(@PathVariable UUID attemptId,
            @Parameter(required = true, description = "Exact 1–200 printable ASCII characters without whitespace; scoped to the authenticated user")
            @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = AcceptResponseBatchRequest.class),
                    examples = @ExampleObject(value = """
                    {"expectedStateVersion":1,"responses":[{"formItemId":"00000000-0000-0000-0000-000000000002","response":{"type":"TRUE_FALSE","answer":true}}]}
                    """))) @Valid @RequestBody AcceptResponseBatchRequest request, BindingResult validation) {
        if (validation.hasErrors()) throw new ResponseBatchException(ResponseBatchException.Reason.MALFORMED);
        if (request.responses().size() > ResponseBatchAcceptanceService.MAX_ITEMS)
            throw new ResponseBatchException(ResponseBatchException.Reason.LIMIT);
        if (request.responses().isEmpty()) throw new ResponseBatchException(ResponseBatchException.Reason.INVALID_ITEMS);
        final CommandReceipt.Key identity;
        try { identity = CommandReceipt.Key.from(key); }
        catch (RuntimeException invalid) { throw new ResponseBatchException(ResponseBatchException.Reason.MALFORMED); }
        var items = request.responses().stream().map(i -> new BatchItem(i.formItemId(), i.response().type(), json.valueToTree(i.response()))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResponseBatchAcknowledgement.from(
                responses.acceptBatch(new BatchCommand(attemptId, request.expectedStateVersion(), identity, items))));
    }
}
