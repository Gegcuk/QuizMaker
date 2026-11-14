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
import uk.gegc.quizmaker.features.attempt.application.DraftCommandService;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

@RestController
@RequestMapping("/api/v2/attempts/{attemptId}/drafts/{formItemId}")
@Tag(name = "Policy-driven Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.draft-commands-enabled", havingValue = "true")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "200", description = "Committed draft command or identical replay", content = @Content(
        schema = @Schema(implementation = DraftCommandAcknowledgement.class), examples = @ExampleObject(value = """
        {"attemptId":"00000000-0000-0000-0000-000000000001","formItemId":"00000000-0000-0000-0000-000000000002","stateVersion":1,"draftVersion":1,"status":"PRESENT","replayed":false}
        """)))
@ApiResponse(responseCode = "400", description = "Malformed request, identifier or key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Absent, foreign, legacy or inaccessible attempt/item", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Stale version, denied action, conflicting replay, busy or expired receipt", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "422", description = "Invalid value or unsupported contract", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "500", description = "Inconsistent saved evidence", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Unavailable storage, retryable transaction or unknown commit outcome", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class DraftCommandController {
    private final DraftCommandService drafts;
    private final ObjectMapper json;
    public DraftCommandController(DraftCommandService drafts, ObjectMapper json) { this.drafts = drafts; this.json = json; }

    private static final String CONTRACT = "Online untimed flat ALL_AT_ONCE practice only. Requires ATTEMPT_CREATE, the bound participant, "
            + "current quiz ownership/full-content editing authority and drafts enabled in the saved policy. "
            + "Server resolves all identity, form and response bindings. Expected state version comes from the attempt view; "
            + "expected draft version 0 means never saved. Draft commands preserve lifecycle, accepted answers, scores and feedback. "
            + "The exact case-sensitive Idempotency-Key is 1–200 printable ASCII characters without whitespace, scoped to the user. "
            + "Identical replay rechecks current access and returns the ORIGINAL versions/status, even after newer changes or completion. "
            + "Changed input conflicts. After unknown outcome retry the same request/key; no automatic retries. "
            + "No offline replay. Disabled by default pending capability activation; existing clients and participant envelopes are unchanged.";

    @PutMapping(consumes = "application/json", produces = "application/json")
    @Operation(operationId = "saveAttemptDraft", summary = "Save a private versioned draft", description = CONTRACT
            + " Five baseline response families use stable saved option UUIDs, never display indexes. Arrays <=100; gap text <=10000 characters; "
            + "normalized response <=65536 UTF-8 bytes. Saving increments the draft version; accepted items cannot be edited.")
    public ResponseEntity<DraftCommandAcknowledgement> save(@PathVariable UUID attemptId, @PathVariable UUID formItemId,
            @Parameter(required = true, description = "Opaque command identity; reuse only for the identical request") @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = SaveDraftRequest.class), examples = @ExampleObject(value = """
                    {"expectedStateVersion":1,"expectedDraftVersion":0,"response":{"type":"TRUE_FALSE","answer":true}}
                    """))) @Valid @RequestBody SaveDraftRequest request, org.springframework.validation.BindingResult validation) {
        if (validation.hasErrors()) throw new ResponseAcceptanceJsonConfig.InvalidRequest();
        return response(drafts.execute(new DraftCommandService.Command(Type.SAVE_DRAFT, attemptId, formItemId,
                request.expectedStateVersion(), request.expectedDraftVersion(), CommandReceipt.Key.from(key),
                request.response().type(), json.valueToTree(request.response()))));
    }

    @DeleteMapping(produces = "application/json")
    @ApiResponse(responseCode = "200", description = "Committed deletion or identical replay", content = @Content(
            schema = @Schema(implementation = DraftCommandAcknowledgement.class), examples = @ExampleObject(value = """
            {"attemptId":"00000000-0000-0000-0000-000000000001","formItemId":"00000000-0000-0000-0000-000000000002","stateVersion":1,"draftVersion":1,"status":"DELETED","replayed":false}
            """)))
    @Operation(operationId = "deleteAttemptDraft", summary = "Delete a private versioned draft", description = CONTRACT
            + " Deletion always advances the matching draft version and retains a content-free tombstone. Deleting a never-saved item at "
            + "version 0 creates DELETED version 1, so delayed first saves conflict. A new key requires the current expected version, including for a tombstone.")
    public ResponseEntity<DraftCommandAcknowledgement> delete(@PathVariable UUID attemptId, @PathVariable UUID formItemId,
            @Parameter(required = true, description = "Opaque command identity; reuse only for the identical request") @RequestHeader("Idempotency-Key") String key,
            @Parameter(description = "Expected attempt version, nonnegative", example = "1") @RequestParam long expectedStateVersion,
            @Parameter(description = "Expected draft version, 0..9223372036854775806", example = "0") @RequestParam long expectedDraftVersion) {
        return response(drafts.execute(new DraftCommandService.Command(Type.DELETE_DRAFT, attemptId, formItemId,
                expectedStateVersion, expectedDraftVersion, CommandReceipt.Key.from(key), null, null)));
    }

    private static ResponseEntity<DraftCommandAcknowledgement> response(DraftCommandService.Result r) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DraftCommandAcknowledgement(
                r.attemptId(), r.formItemId(), r.stateVersion(), r.draftVersion(), r.status(), r.replayed()));
    }
}
