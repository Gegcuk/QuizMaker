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
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.NavigationService;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

@RestController
@RequestMapping("/api/v1/attempts")
@Tag(name = "Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.navigation-commands-enabled", havingValue = "true")
public class NavigationController {
    private final NavigationService navigation;
    public NavigationController(NavigationService navigation) { this.navigation = navigation; }

    @PostMapping(value = "/{attemptId}/navigation", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "navigateAttempt", summary = "Navigate an authorized flat fixed form",
            description = "Requires authentication, ATTEMPT_READ, the bound participant and current quiz ownership/full question editing authority. " +
                    "Uses captured FREE/SEQUENTIAL rules and already delivered ALL_AT_ONCE content. SEQUENTIAL defaults to answer-before-next; " +
                    "skip/return and accepted-item revisit require captured authorization. No answer, grade or finality is changed. " +
                    "FREE has no stored position. Supply expectedStateVersion and, for SEQUENTIAL, expectedNavigationVersion. " +
                    "Idempotency-Key is case-sensitive, 1–200 printable ASCII characters without whitespace, scoped to the current user. " +
                    "Identical retries return original metadata after current READ authorization, even after later commands. Changed input conflicts. " +
                    "Retry unknown commit outcomes with the same request/key; no automatic fallback. Online only, no pagination/sorting. " +
                    "Disabled by default pending capability conformance/activation; legacy routes remain compatible.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Committed navigation or identical replay", content = @Content(
            schema = @Schema(implementation = NavigationAcknowledgement.class), examples = @ExampleObject(value = """
            {"attemptId":"00000000-0000-0000-0000-000000000001","formItemId":"00000000-0000-0000-0000-000000000002","stateVersion":1,"navigation":{"currentFormItemId":"00000000-0000-0000-0000-000000000002","version":2},"allowedActions":["VIEW_ATTEMPT","NAVIGATE_TO_ITEM"],"replayed":false}
            """)))
    @ApiResponse(responseCode = "400", description = "Malformed input/key or invalid version shape", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Absent, foreign, hidden, legacy or inaccessible attempt/item", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Stale/inactive, answer required, prohibited transition, conflicting replay, busy or expired receipt", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Unsupported saved contract", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Inconsistent saved evidence", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Storage unavailable, retryable transaction or unknown commit outcome", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<NavigationAcknowledgement> navigate(@PathVariable UUID attemptId,
            @Parameter(required = true, description = "Exact command identity; never reuse for changed input") @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = NavigationRequest.class), examples = @ExampleObject(value = """
                    {"formItemId":"00000000-0000-0000-0000-000000000002","expectedStateVersion":1,"expectedNavigationVersion":1}
                    """))) @Valid @RequestBody NavigationRequest request, BindingResult validation) {
        if (validation.hasErrors()) throw new ResponseAcceptanceJsonConfig.InvalidRequest();
        var result = navigation.navigate(new NavigationService.Command(attemptId, request.formItemId(), request.expectedStateVersion(),
                request.expectedNavigationVersion(), CommandReceipt.Key.from(key)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NavigationAcknowledgement.from(result));
    }
}
