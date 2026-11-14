package uk.gegc.quizmaker.features.attempt.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.api.dto.ParticipantAttemptView;
import uk.gegc.quizmaker.features.attempt.application.ParticipantAttemptViewService;

@RestController
@RequestMapping("/api/v2/attempts")
@Tag(name = "Policy-driven Attempts")
@ConditionalOnProperty(name = "quizmaker.attempts.participant-view-enabled", havingValue = "true")
public class ParticipantAttemptViewController {
    private final ParticipantAttemptViewService views;

    public ParticipantAttemptViewController(ParticipantAttemptViewService views) {
        this.views = views;
    }

    @Operation(operationId = "readParticipantAttempt", summary = "Read a policy-driven attempt", description = "Read the bound participant attempt from its saved revision and form. Requires ATTEMPT_READ and current quiz ownership or full question editing authority. Wrong, missing, legacy and unauthorized attempts share 404. Result categories require committed release and current RESULT access; caller flags grant no disclosure. TIMED views report the saved deadline and server time without asserting terminal processing. Online only; read-only, no idempotency key, pagination, filtering or sorting. Cache-Control: no-store. Disabled by default pending separate activation.")
    @ApiResponse(responseCode = "200", description = "Attempt details returned",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ParticipantAttemptView.class), examples = {
                        @ExampleObject(name = "policyDriven", value = """
                            {"representation":"POLICY_DRIVEN","schemaVersion":1,"attemptId":"10000000-0000-0000-0000-000000000001",
                             "quizId":"20000000-0000-0000-0000-000000000001","quizRevisionId":"30000000-0000-0000-0000-000000000001",
                             "formId":"40000000-0000-0000-0000-000000000001","stateVersion":1,"policyVersion":1,
                             "execution":"IN_PROGRESS","answerCollection":"NOT_STARTED","startedAt":"2026-11-06T12:00:00Z",
                             "timing":"UNTIMED","contentVisibility":"VISIBLE","items":[{"id":"50000000-0000-0000-0000-000000000001",
                             "position":0,"type":"TRUE_FALSE","questionText":"Water is a liquid at room temperature.","content":{"kind":"TRUE_FALSE"}}],
                             "allowedActions":["VIEW_ATTEMPT"]}
                            """),
                        @ExampleObject(name = "timedFoundation", value = """
                            {"representation":"POLICY_DRIVEN","schemaVersion":1,"attemptId":"10000000-0000-0000-0000-000000000001",
                             "quizId":"20000000-0000-0000-0000-000000000001","quizRevisionId":"30000000-0000-0000-0000-000000000001",
                             "formId":"40000000-0000-0000-0000-000000000001","stateVersion":1,"policyVersion":1,
                             "execution":"IN_PROGRESS","answerCollection":"NOT_STARTED","startedAt":"2026-11-06T17:45:00Z",
                             "timing":"TIMED","timingDetails":{"version":1,"startedAt":"2026-11-06T17:45:00Z",
                             "effectiveDeadline":"2026-11-06T18:00:00Z","graceSeconds":0,"serverTime":"2026-11-06T17:50:00Z","expired":false},
                             "contentVisibility":"WITHHELD","allowedActions":["VIEW_ATTEMPT"]}
                            """),
                        @ExampleObject(name = "releasedPractice", value = ParticipantReviewExamples.REVIEW)
                    }
            )
    )
    @ApiResponse(responseCode = "404", description = "Attempt not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "400", description = "Invalid attempt identifier", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Target attempt is currently busy", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Unsupported saved target representation or media integration", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Corrupt target evidence; no partial view", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Target read temporarily unavailable; no partial view", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{attemptId}")
    public ResponseEntity<ParticipantAttemptView> getAttempt(
            @Parameter(description = "UUID of the attempt to retrieve", required = true)
            @PathVariable UUID attemptId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views.read(attemptId));
    }

    @Operation(operationId = "readParticipantAttemptReview", summary = "Read a policy-driven attempt review", description = "Read the bound participant completed review. Requires ATTEMPT_READ, current resource access, committed release and per-category RESULT access. Include parameters only narrow permitted fields. Wrong, missing, legacy and unauthorized attempts share 404; pending review returns 409. Online only; no pagination, sorting or idempotency key. Cache-Control: no-store.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt review returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ParticipantAttemptView.class),
                            examples = @ExampleObject(name = "releasedPractice", value = ParticipantReviewExamples.REVIEW)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Attempt is not completed yet",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "400", description = "Invalid attempt identifier", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Unsupported saved target contract", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Corrupt target evidence; no partial review", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Target read temporarily unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{attemptId}/review")
    public ResponseEntity<ParticipantAttemptView> getAttemptReview(
            @Parameter(description = "UUID of the attempt to review", required = true)
            @PathVariable UUID attemptId,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Include user's submitted answers in the response",
                    example = "true"
            )
            @RequestParam(name = "includeUserAnswers", defaultValue = "true") boolean includeUserAnswers,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Include correct answers and explanations in the response",
                    example = "true"
            )
            @RequestParam(name = "includeCorrectAnswers", defaultValue = "true") boolean includeCorrectAnswers,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Include question context (text, hint, attachments, safe content) for rendering",
                    example = "true"
            )
            @RequestParam(name = "includeQuestionContext", defaultValue = "true") boolean includeQuestionContext
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(views.review(attemptId, includeUserAnswers, includeCorrectAnswers, includeQuestionContext));
    }

    @Operation(operationId = "readParticipantAttemptAnswerKey", summary = "Read a policy-driven answer key", description = "Read the bound participant answer key after committed release. Requires ATTEMPT_READ and current per-category RESULT access. Uses the exact saved form; submitted responses are excluded. Wrong, missing, legacy and unauthorized attempts share 404. Online only; no pagination, filtering, sorting or idempotency key. Cache-Control: no-store.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer key returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ParticipantAttemptView.class),
                            examples = @ExampleObject(name = "releasedPractice", value = ParticipantReviewExamples.ANSWER_KEY)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Attempt is not completed yet",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "400", description = "Invalid attempt identifier", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Unsupported saved target contract", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Corrupt target evidence; no partial answer key", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Target read temporarily unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{attemptId}/answer-key")
    public ResponseEntity<ParticipantAttemptView> getAttemptAnswerKey(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views.review(attemptId, false, true, true));
    }
}
