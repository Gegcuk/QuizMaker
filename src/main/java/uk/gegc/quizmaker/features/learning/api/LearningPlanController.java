package uk.gegc.quizmaker.features.learning.api;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.learning.application.LearningPlanService;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import java.time.Instant;
import java.util.UUID;
import static uk.gegc.quizmaker.features.learning.api.LearningPlanApi.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="quizmaker.learning",name={"sessions-enabled","custom-plans-enabled"},havingValue="true")
@RequestMapping(value="/api/v1/learning",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="bearerAuth")
@ApiResponse(responseCode="400",description="Malformed, oversized or invalid input; no mutation.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="401",description="Authentication required.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="404",description="Absent or inaccessible private plan, source, question or binding.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="409",description="Changed command, stale version/cycle, active session or unsupported lifecycle transition.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="422",description="Unknown configuration or strategy version; no fallback.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="429",description="New mutation exceeds the shared Learning command budget; exact replay consumes no credit.",headers=@Header(name="Retry-After",description="Whole seconds"),content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="500",description="Retained evidence is inconsistent or caller transaction is incompatible.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="503",description="Persistence or required batch configuration unavailable; retry the original command after uncertain commit.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
public class LearningPlanController {
    private final LearningPlanService plans;
    private static final String EXAMPLE="""
        {"commandId":"11111111-1111-4111-8111-111111111111","expectedVersion":0,
         "definition":{"version":1,"name":"Anatomy","intervals":[{"value":1,"unit":"DAYS"},{"value":3,"unit":"DAYS"}],
         "mode":"FINITE","coefficient":2,"timeZone":"Europe/London","localTime":"09:00"}}
        """;
    @PostMapping(value="/plans",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Create a private interval plan",description="Active account only. 1–100 nondecreasing intervals, 1 minute–3650 days, whole-minute duration precision. Equal steps remain distinct. Calendar days require explicit zone and local time; coefficient defaults to 2 and must be at least 1. No enrollment or review. Reuse commandId and exact input after response loss.")
    @ApiResponse(responseCode="200",description="Original command acknowledgement; only plan is non-null.")
    public ResponseEntity<Acknowledgement> create(@RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content=@Content(schema=@Schema(implementation=SaveRequest.class),examples=@ExampleObject(name="First private plan",value=EXAMPLE))) SaveRequest r) {
        return ok(plans.create(new LearningPlanService.Save(r.commandId(),r.expectedVersion(),r.definition())));
    }
    @PostMapping(value="/plans/{planId}/versions",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Create an immutable plan version",description="Owner only. expectedVersion is the current head counter, including pause changes. Existing cycles and pinned overrides keep their version/due. Quiz defaults resolve the new head only on a new enrollment. Replay returns the original acknowledgement.")
    @ApiResponse(responseCode="200",description="Original plan-version acknowledgement.",content=@Content(schema=@Schema(implementation=Acknowledgement.class)))
    public ResponseEntity<Acknowledgement> edit(@PathVariable UUID planId,@RequestBody SaveRequest r) {
        return ok(plans.edit(planId,new LearningPlanService.Save(r.commandId(),r.expectedVersion(),r.definition())));
    }
    @PostMapping(value="/plans/{planId}/pause",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Pause or resume a private plan",description="Owner only. Hide active custom cycles from due/upcoming selection while preserving sequence, stage and UTC due instant. Resume returns one overdue item, without a backlog or advancement.")
    @ApiResponse(responseCode="200",description="Original pause/resume acknowledgement.",content=@Content(schema=@Schema(implementation=Acknowledgement.class)))
    public ResponseEntity<Acknowledgement> pause(@PathVariable UUID planId,@RequestBody PauseRequest r) {
        return ok(plans.pause(planId,new LearningPlanService.Pause(r.commandId(),r.expectedVersion(),r.paused())));
    }
    @GetMapping("/plans/{planId}") @Operation(summary="Read an owned plan",description="Owner only; reads consume no command credit.")
    @ApiResponse(responseCode="200",description="Current private plan head.",content=@Content(schema=@Schema(implementation=View.class)))
    public ResponseEntity<View> read(@PathVariable UUID planId) { return ok(plans.read(planId)); }
    @GetMapping("/plans") @Operation(summary="Page private plans",description="Owner only. Required limit is bounded by configured Learning maxBatchSize. Ascending unsigned UUID keyset; next is nullable. Cursor is not a snapshot or access grant.")
    @ApiResponse(responseCode="200",description="Bounded typed plan page.",content=@Content(schema=@Schema(implementation=PlanPage.class)))
    public ResponseEntity<PlanPage> list(@RequestParam int limit,@RequestParam(required=false) UUID after) { return ok(plans.list(limit,after)); }
    @GetMapping("/plans/{planId}/versions") @Operation(summary="Page immutable plan history",description="Owner only. Ascending configuration versions; next is nullable. Pause changes do not create a configuration version. Required limit uses configured Learning maxBatchSize.")
    @ApiResponse(responseCode="200",description="Bounded immutable configuration page.",content=@Content(schema=@Schema(implementation=VersionPage.class)))
    public ResponseEntity<VersionPage> history(@PathVariable UUID planId,@RequestParam int limit,@RequestParam(defaultValue="0") long after) { return ok(plans.history(planId,limit,after)); }
    @PostMapping(value="/plan-bindings",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Set or clear a personal plan binding",description="Active current author with access to the exact publication. Binding a plan does not enroll or reschedule. expectedVersion=0 means absent; a clear retains its version. Another user's plan is never bindable. Recheck current authority on exact replay.")
    @ApiResponse(responseCode="200",description="Original binding acknowledgement.",content=@Content(schema=@Schema(implementation=Acknowledgement.class)))
    public ResponseEntity<Acknowledgement> bind(@RequestBody BindRequest r) {
        return ok(plans.bind(new LearningPlanService.Bind(r.commandId(),r.source().domain(),r.questionRevisionId(),r.expectedVersion(),r.planId(),r.planVersion())));
    }
    @GetMapping("/plan-bindings") @Operation(summary="Read a personal plan binding",description="Current publication authority required. Omit questionRevisionId for the quiz default. An absent/cleared binding has null planId; this does not enroll.")
    @ApiResponse(responseCode="200",description="Current personal binding or explicit absent binding.",content=@Content(schema=@Schema(implementation=Binding.class)))
    public ResponseEntity<Binding> binding(@Valid @ParameterObject SourceQuery source,@RequestParam(required=false) UUID questionRevisionId) { return ok(plans.binding(source.domain(),questionRevisionId)); }
    @PostMapping(value="/plan-cycles",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Enroll or confirm a new schedule cycle",description="Current active author and exact publication membership required. CUSTOM captures the pinned question override or current quiz default. Existing enrollment keeps its cycle. With custom-reviews-enabled, a fresh ENROLL command also prepares the whole publication if needed; older exact receipts remain unchanged. SWITCH/RESTART require confirmation and exact cycle/version/sequence; active sessions block replacement. One shared FSRS/custom slot preserves history. The separate custom-reviews-enabled opt-in permits custom review through the canonical Learning session commands. FSRS resumes through its existing commands after a switch back.")
    @ApiResponse(responseCode="200",description="Original cycle acknowledgement.",content=@Content(schema=@Schema(implementation=Acknowledgement.class)))
    public ResponseEntity<Acknowledgement> change(@RequestBody ChangeRequest r) {
        return ok(plans.change(new LearningPlanService.Change(r.commandId(),r.source().domain(),r.questionRevisionId(),r.action(),r.strategy(),r.expected()==null?null:r.expected().domain(),r.confirmed(),r.targetPlanId(),r.targetPlanVersion())));
    }
    @GetMapping("/plan-cycles/{questionRevisionId}") @Operation(summary="Read the active cycle",description="Current publication authority required. Metadata only; stage/sequence are captured facts, not mastery. No implicit enrollment.")
    @ApiResponse(responseCode="200",description="Current active cycle metadata.",content=@Content(schema=@Schema(implementation=Cycle.class)))
    public ResponseEntity<Cycle> cycle(@Valid @ParameterObject SourceQuery source,@PathVariable UUID questionRevisionId) { return ok(plans.cycle(source.domain(),questionRevisionId)); }
    @GetMapping("/plan-cycles") @Operation(summary="Page custom cycle metadata",description="Current publication authority required. ALL includes paused/completed active cycles; DUE/UPCOMING exclude them. Due is compared with server time in UTC. Sort by dueAt then unsigned question UUID. Supply both cursor fields or neither. Limit uses configured Learning maxBatchSize. This read neither advances a schedule nor starts a custom session.")
    @ApiResponse(responseCode="200",description="Bounded typed custom cycle page.",content=@Content(schema=@Schema(implementation=CyclePage.class)))
    public ResponseEntity<CyclePage> cycles(@Valid @ParameterObject SourceQuery source,@RequestParam Window window,@RequestParam int limit,
            @RequestParam(required=false) Instant afterTime,@RequestParam(required=false) UUID afterQuestion) {
        uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require((afterTime==null)==(afterQuestion==null),
                uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.INVALID_INPUT);
        return ok(plans.cycles(source.domain(),window,limit,afterTime==null?null:new Cursor(afterTime,afterQuestion)));
    }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
