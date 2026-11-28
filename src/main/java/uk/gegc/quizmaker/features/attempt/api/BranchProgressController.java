package uk.gegc.quizmaker.features.attempt.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.BranchAdvanceAcknowledgement;
import uk.gegc.quizmaker.features.attempt.api.dto.BranchAdvanceRequest;
import uk.gegc.quizmaker.features.attempt.application.BranchingAttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException;

@RestController
@RequestMapping("/api/v2/attempts")
@Tag(name = "Policy-driven Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.branch-participant-enabled", havingValue = "true")
public class BranchProgressController {
    private final BranchingAttemptService branches;

    public BranchProgressController(BranchingAttemptService branches) {
        this.branches = branches;
    }

    @PostMapping(value = "/{attemptId}/branch-progress/next", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "advanceBranchProgress", summary = "Advance along a bound scenario path",
            description = "Online only. Requires ATTEMPT_CREATE, the bound participant and current quiz ownership/full question editing authority. "
                    + "Uses the saved graph and accepted response if required. Terminal readiness requires an explicit submit; no score or result is created here. "
                    + "Idempotency-Key is a UUID scoped to this attempt. An identical retry of a committed transition returns the original acknowledgement "
                    + "after current authorization. On unknown commit retry with the same UUID and body. An unanswered fork returns 409 without a committed decision. "
                    + "Use the participant view to reconcile current progress. Disabled by default; no scenario admission or public start. Cache-Control: no-store.",
            parameters = @Parameter(name = "Idempotency-Key", in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
                    required = true, schema = @Schema(type = "string", format = "uuid")),
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Committed transition or its exact replay",
            content = @Content(schema = @Schema(implementation = BranchAdvanceAcknowledgement.class), examples = {
                    @ExampleObject(name = "appended", value = """
                            {"outcome":"APPENDED","formVersion":2,"formItemId":"00000000-0000-0000-0000-000000000001"}
                            """),
                    @ExampleObject(name = "terminalReady", value = """
                            {"outcome":"TERMINAL_READY","formVersion":2}
                            """)
            }))
    @ApiResponse(responseCode = "400", description = "Malformed identifier, key or version", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Absent, inaccessible or non-branching attempt", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Stale, conflicting replay, inactive attempt or answer required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Unsupported saved contract", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Inconsistent saved evidence", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Unavailable, retryable transaction or unknown commit", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BranchAdvanceAcknowledgement> next(
            @PathVariable UUID attemptId, WebRequest http,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = BranchAdvanceRequest.class), examples = @ExampleObject(value = """
                    {"expectedStateVersion":2,"expectedFormVersion":1}
                    """)))
            @Valid @RequestBody BranchAdvanceRequest request, BindingResult validation) {
        if (validation.hasErrors()) throw new BranchingException(BranchingException.Reason.INVALID);
        var commandId = commandId(http.getHeaderValues("Idempotency-Key"));
        var result = branches.advance(new BranchingAttemptService.Command(attemptId, commandId,
                request.expectedStateVersion(), request.expectedFormVersion()));
        var outcome = switch (result.outcome()) {
            case APPENDED -> BranchAdvanceAcknowledgement.Outcome.APPENDED;
            case TERMINAL_READY -> BranchAdvanceAcknowledgement.Outcome.TERMINAL_READY;
            case WAITING_FOR_ANSWER -> throw new BranchingException(BranchingException.Reason.ANSWER_REQUIRED);
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new BranchAdvanceAcknowledgement(outcome, result.formVersion(), result.formItemId()));
    }

    private static UUID commandId(String[] values) {
        if (values == null || values.length != 1 || values[0].length() != 36)
            throw new BranchingException(BranchingException.Reason.INVALID);
        try {
            var id = UUID.fromString(values[0]);
            if (!id.toString().equalsIgnoreCase(values[0])) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new BranchingException(BranchingException.Reason.INVALID);
        }
    }
}
