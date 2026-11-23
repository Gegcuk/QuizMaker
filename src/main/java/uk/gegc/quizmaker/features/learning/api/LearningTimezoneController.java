package uk.gegc.quizmaker.features.learning.api;

import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.application.LearningCycleService;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.Acknowledgement;

@RestController
@RequiredArgsConstructor
@RequestMapping(value="/api/v1/learning/plan-cycles/{questionRevisionId}/timezone",produces=MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix="quizmaker.learning",name={"sessions-enabled","custom-plans-enabled","custom-reviews-enabled"},havingValue="true")
@SecurityRequirement(name="bearerAuth")
@ApiResponse(responseCode="400",description="Invalid zone, confirmation or strict bounded input.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="401",description="Authentication required.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="404",description="Absent or inaccessible source/question/cycle.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="409",description="Stale preview, changed command, completed cycle or active captured session. No partial rescheduling.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="415",description="Only application/json request bodies are supported.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="422",description="Unknown retained configuration/strategy.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="429",description="Shared Learning command budget exhausted; exact replay consumes no credit.",headers=@io.swagger.v3.oas.annotations.headers.Header(name="Retry-After",description="Whole seconds"),content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="500",description="Inconsistent evidence or incompatible caller transaction.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="503",description="Persistence unavailable. Reconcile the original confirmation command after response loss.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
public class LearningTimezoneController {
    private final LearningCycleService cycles;
    private static final String PREVIEW="""
        {"source":{"kind":"QUIZ_REVISION","quizId":"11111111-1111-4111-8111-111111111111",
        "quizRevisionId":"22222222-2222-4222-8222-222222222222","revisionSchemaVersion":1},"timeZone":"America/New_York"}
        """;
    private static final String CONFIRM="""
        {"commandId":"33333333-3333-4333-8333-333333333333","confirmed":true,"preview":{
        "source":{"version":1,"quizId":"11111111-1111-4111-8111-111111111111",
        "quizRevisionId":"22222222-2222-4222-8222-222222222222","revisionSchemaVersion":1},
        "questionRevisionId":"44444444-4444-4444-8444-444444444444",
        "expected":{"cycleId":"55555555-5555-4555-8555-555555555555","cycleVersion":1,"sequence":1},
        "scheduleVersion":1,"timeZone":"America/New_York","previousDueAt":"2026-11-24T09:00:00Z","nextDueAt":"2026-11-24T14:00:00Z"}}
        """;
    @Schema(name="PreviewLearningTimezoneRequest")
    public record PreviewRequest(@NotNull @Valid LearningSessionApi.SourceRef source,@NotBlank String timeZone) {
        @Override public String toString() { return "PreviewLearningTimezoneRequest[redacted]"; }
    }
    @Schema(name="ConfirmLearningTimezoneRequest")
    public record ConfirmRequest(@NotNull UUID commandId,@NotNull @Valid LearningCycleService.Preview preview,@NotNull Boolean confirmed) {
        @Override public String toString() { return "ConfirmLearningTimezoneRequest[redacted]"; }
    }
    @PostMapping(value="/preview",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Preview a custom cycle timezone change",description="Current author/owner of the exact published source only. Explicit timezone, for example America/New_York; selected local time remains captured from the immutable plan. Calculate from the last actual advancing review; no review, command credit or due change. Minutes/hours keep their elapsed due instant. An initial unreviewed cycle remains immediately available. Other cycles and FSRS are unchanged.")
    @ApiResponse(responseCode="200",description="Exact cycle/schedule version and old/new UTC due instants.",content=@Content(schema=@Schema(implementation=LearningCycleService.Preview.class)))
    public ResponseEntity<LearningCycleService.Preview> preview(@PathVariable UUID questionRevisionId,@Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(implementation=PreviewRequest.class),examples=@ExampleObject(value=PREVIEW))) PreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cycles.preview(request.source().domain(),questionRevisionId,request.timeZone()));
    }
    @PostMapping(value="/confirm",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Confirm the exact timezone preview",description="Same current publication authority as preview, including replay. confirmed must be true; question in the path must equal the preview. Finish captured sessions first. A later review or timezone change makes the preview conflict; request a fresh preview. Atomically preserve stage/review count and record calendar rescheduling, acknowledgement and one command credit. Repeat identical commandId/body after response loss. No automatic profile-wide rescheduling, plan edit or review event.")
    @ApiResponse(responseCode="200",description="Original cycle acknowledgement, also on exact replay.",content=@Content(schema=@Schema(implementation=Acknowledgement.class)))
    public ResponseEntity<Acknowledgement> confirm(@PathVariable UUID questionRevisionId,@Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(implementation=ConfirmRequest.class),examples=@ExampleObject(value=CONFIRM))) ConfirmRequest request) {
        uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require(questionRevisionId.equals(request.preview().questionRevisionId()),
                uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.INVALID_INPUT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cycles.confirm(request.commandId(),request.preview(),request.confirmed()));
    }
}
