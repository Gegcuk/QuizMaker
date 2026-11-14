package uk.gegc.quizmaker.features.attempt.api;

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
import uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

@RestController
@RequestMapping("/api/v2/attempts")
@Tag(name = "Policy-driven Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.submission-enabled", havingValue = "true")
public class AttemptSubmissionController {
    private final AttemptSubmissionService submissions;
    public AttemptSubmissionController(AttemptSubmissionService submissions) { this.submissions = submissions; }
    @PostMapping(value = "/{attemptId}/submit", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "submitAttempt", summary = "Submit an attempt once",
            description = "Online authenticated flat untimed immediate-lock practice. Requires ATTEMPT_CREATE, the bound participant and current quiz ownership/full question editing authority. " +
                    "No caller user, policy, scope, deadline or answer payload is accepted. Use the current optimistic state version and explicitly confirm completion, including unanswered items. " +
                    "The saved require-all setting is authoritative: when enabled, missing answers deny without writes; otherwise omissions remain unanswered. " +
                    "Idempotency-Key is case-sensitive, 1–200 printable ASCII characters without whitespace, scoped to the authenticated user across commands. " +
                    "Identical retries return the original submission/version after current authorization; changed input or fresh terminal submission conflicts. " +
                    "After an unknown outcome, reconcile by resending the exact same command/key; no automatic retries. The response is not a grading or release acknowledgement. " +
                    "No partial writes, offline mutations or new guest/assignment/timing support. Disabled by default until #609; legacy /complete and existing web/iOS contracts are unchanged.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Committed submission or identical replay", content = @Content(
            schema = @Schema(implementation = AttemptSubmitAcknowledgement.class), examples = @ExampleObject(value = """
            {"attemptId":"00000000-0000-0000-0000-000000000001","submissionId":"00000000-0000-0000-0000-000000000002","stateVersion":10,"replayed":false}
            """)))
    @ApiResponse(responseCode = "400", description = "Malformed request/key", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    @ApiResponse(responseCode = "404", description = "Missing or inaccessible attempt", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    @ApiResponse(responseCode = "409", description = "Conflicting key/input, stale version, terminal state, busy command or expired receipt", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    @ApiResponse(responseCode = "422", description = "Incomplete required answers, confirmation required or unsupported saved capability", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    @ApiResponse(responseCode = "500", description = "Corrupt server evidence; no partial writes", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    @ApiResponse(responseCode = "503", description = "Unavailable, fully rolled-back retryable conflict or unknown commit outcome; keep command identity", content = @Content(schema = @Schema(implementation = AttemptSubmitProblem.class)))
    public ResponseEntity<AttemptSubmitAcknowledgement> submit(@PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = AttemptSubmitRequest.class), examples = @ExampleObject(value = """
                    {"expectedStateVersion":9,"completionConfirmed":true}
                    """))) @Valid @RequestBody AttemptSubmitRequest request, org.springframework.validation.BindingResult validation) {
        if (validation.hasErrors()) throw new AttemptSubmissionJsonConfig.InvalidRequest();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AttemptSubmitAcknowledgement.from(submissions.submit(
                new AttemptSubmissionService.Command(attemptId, request.expectedStateVersion(), request.completionConfirmed(), CommandReceipt.Key.from(key)))));
    }
}
