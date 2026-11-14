package uk.gegc.quizmaker.features.attempt.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse;
import uk.gegc.quizmaker.features.attempt.application.AttemptDeliveryService;

@RestController
@RequestMapping("/api/v2/attempts")
@Tag(name = "Policy-driven Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.flat-delivery-enabled", havingValue = "true")
public class AttemptFormController {
    private final AttemptDeliveryService delivery;
    public AttemptFormController(AttemptDeliveryService delivery) { this.delivery = delivery; }

    @GetMapping(value = "/{attemptId}/form", produces = "application/json")
    @Operation(operationId = "readAttemptForm", summary = "Read the saved flat attempt form",
            description = "Requires authentication, ATTEMPT_READ, the bound participant, and current quiz ownership or full question editing authority. " +
                    "Active flat ALL_AT_ONCE returns the complete permitted form; ONE_AT_A_TIME returns only its current permitted item and navigation version. Returns immutable text-only content for the five baseline question families, " +
                    "stable item/option IDs and order; author edits do not replace saved content. No answer keys, correctness, explanations or private provenance. " +
                    "Include flags confer no authority. Wrong, missing, legacy and unauthorized attempts share 404. " +
                    "Read-only and repeatable without an idempotency key; no partial form on failure. No filters, sorting or pagination. " +
                    "Responses must not be cached. Disabled by default pending separate activation; existing routes are unchanged.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Saved permitted content; Cache-Control: no-store",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AttemptFormResponse.class), examples = {@ExampleObject(name = "allAtOnce", value = """
                    {"attemptId":"10000000-0000-0000-0000-000000000001","stateVersion":1,
                     "formId":"20000000-0000-0000-0000-000000000001","schemaVersion":1,
                     "items":[{"id":"30000000-0000-0000-0000-000000000001","position":0,"type":"TRUE_FALSE",
                     "questionText":"Water freezes at 0 degrees Celsius at standard pressure.","content":{"kind":"TRUE_FALSE"}}],
                     "allowedActions":["VIEW_ATTEMPT"]}
                    """), @ExampleObject(name = "oneAtATime", value = """
                    {"attemptId":"10000000-0000-0000-0000-000000000001","stateVersion":2,
                     "formId":"20000000-0000-0000-0000-000000000001","schemaVersion":1,
                     "items":[{"id":"30000000-0000-0000-0000-000000000002","position":1,"type":"TRUE_FALSE",
                     "questionText":"Water freezes at 0 degrees Celsius at standard pressure.","content":{"kind":"TRUE_FALSE"}}],
                     "allowedActions":["VIEW_ATTEMPT"],
                     "navigation":{"currentFormItemId":"30000000-0000-0000-0000-000000000002","version":2}}
                    """)}))
    @ApiResponse(responseCode = "400", description = "Invalid attempt UUID", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Attempt unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Attempt is inactive or currently busy; reconcile before retry", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Unsupported saved delivery contract", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Invalid saved form evidence; no partial response", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Storage temporarily unavailable; no content returned", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AttemptFormResponse> read(@PathVariable UUID attemptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AttemptFormResponse.from(delivery.read(attemptId)));
    }
}
