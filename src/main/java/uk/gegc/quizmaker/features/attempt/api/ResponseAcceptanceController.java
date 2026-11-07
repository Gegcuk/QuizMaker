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
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

@RestController
@RequestMapping("/api/v1/attempts")
@Tag(name = "Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.response-acceptance-enabled", havingValue = "true")
public class ResponseAcceptanceController {
    private final ResponseAcceptanceService responses;
    private final ObjectMapper json;
    public ResponseAcceptanceController(ResponseAcceptanceService responses, ObjectMapper json) { this.responses = responses; this.json = json; }

    @PostMapping(value = "/{attemptId}/responses", produces = "application/json", consumes = "application/json")
    @Operation(operationId = "acceptAttemptResponse", summary = "Accept one immutable response",
            description = "Requires authentication, ATTEMPT_CREATE, the bound participant and current quiz ownership/full question editing authority. " +
                    "Online immediate-lock, untimed ALL_AT_ONCE practice only. Uses saved form-item and option UUIDs, never display positions. " +
                    "The expectedStateVersion is the optimistic counter returned by the attempt view. The exact case-sensitive Idempotency-Key " +
                    "contains 1–200 printable ASCII characters without whitespace and is scoped to the authenticated user. Identical retries return " +
                    "the original acceptance/version after current authorization; changed key input or a second answer conflicts. Reuse the exact " +
                    "request after an unknown outcome. No automatic retries or partial writes. Five baseline response families; arrays at most 100, " +
                    "strings at most 10000 characters and normalized response JSON at most 65536 UTF-8 bytes. Empty multi-choice/order/gap arrays retain v1 validation semantics. " +
                    "No grading/feedback is performed here. Legacy /answers remains unchanged. Disabled by default pending #609 activation.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Committed acceptance or identical replay", content = @Content(
            schema = @Schema(implementation = AcceptedResponseAcknowledgement.class), examples = @ExampleObject(value = """
            {"attemptId":"00000000-0000-0000-0000-000000000001","acceptedResponseId":"00000000-0000-0000-0000-000000000003","stateVersion":2,"replayed":false}
            """)))
    @ApiResponse(responseCode = "400", description = "Malformed request or key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Absent, foreign, legacy or inaccessible attempt/item", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Stale/inactive/locked, conflicting replay, busy or expired receipt", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Invalid response or unsupported saved contract", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Inconsistent saved evidence", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Storage unavailable, retryable transaction or unknown commit outcome", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AcceptedResponseAcknowledgement> accept(@PathVariable UUID attemptId,
            @Parameter(description = "Exact opaque command identity; do not reuse for different input", required = true)
            @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = AcceptResponseRequest.class), examples = @ExampleObject(value = """
                    {"formItemId":"00000000-0000-0000-0000-000000000002","expectedStateVersion":1,"response":{"type":"TRUE_FALSE","answer":true}}
                    """))) @Valid @RequestBody AcceptResponseRequest request, org.springframework.validation.BindingResult validation) {
        // Avoid Spring's MethodArgumentNotValidException diagnostics containing rejected answers.
        if (validation.hasErrors()) throw new ResponseAcceptanceJsonConfig.InvalidRequest();
        var result = responses.accept(new ResponseAcceptanceService.Command(attemptId, request.formItemId(), request.expectedStateVersion(),
                CommandReceipt.Key.from(key), request.response().type(), json.valueToTree(request.response())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AcceptedResponseAcknowledgement.from(result));
    }
}
