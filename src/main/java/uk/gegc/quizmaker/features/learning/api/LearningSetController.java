package uk.gegc.quizmaker.features.learning.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.learning.application.LearningSetService;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSet.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="quizmaker.learning",name={"sessions-enabled","sets-enabled"},havingValue="true")
@RequestMapping(value="/api/v1/learning",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="bearerAuth")
@ApiResponse(responseCode="400",description="Invalid, oversized or malformed input/cursor.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="401",description="Authentication required.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="404",description="Absent/deleted set, foreign owner or inaccessible exact publication/member. No existence disclosure.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="409",description="Changed command, stale set/graph version, structural cycle or database lock timeout. Restart paging after a graph change.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="422",description="Unsupported source/strategy/evidence version. Custom selection requires both custom capability flags.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="429",description="New mutation exceeds the shared Learning command budget. Reads and exact replays consume no credit.",headers=@Header(name="Retry-After",description="Whole seconds"),content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="500",description="Inconsistent retained evidence or incompatible transaction.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
@ApiResponse(responseCode="503",description="Persistence or configured batch bound unavailable. Retry the original command after an uncertain commit.",content=@Content(schema=@Schema(implementation=ProblemDetail.class)))
public class LearningSetController {
    private final LearningSetService sets;
    @Schema(name="CreateLearningSetRequest")
    public record CreateRequest(@NotNull UUID commandId,@NotBlank @Size(max=255) String name) {
        @Override public String toString() { return "CreateLearningSetRequest[redacted]"; }
    }
    @Schema(name="ChangeLearningSetMemberRequest")
    public record ChangeRequest(@NotNull UUID commandId,@Positive long expectedVersion,
                                @NotNull @Valid Member member,@NotNull Boolean present) { }
    @Schema(name="DeleteLearningSetRequest")
    public record DeleteRequest(@NotNull UUID commandId,@Positive long expectedVersion) { }

    @PostMapping(value="/sets",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Create a private Learning set",description="Active account only. Name has 1–255 characters with no leading/trailing whitespace or control characters. Membership never grants source access. Retry the same commandId and complete input after response loss; all set mutations share one per-learner command namespace.")
    @ApiResponse(responseCode="200",description="Original durable acknowledgement.")
    public ResponseEntity<Acknowledgement> createLearningSet(@RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content=@Content(schema=@Schema(implementation=CreateRequest.class),examples=@ExampleObject(name="Request",value="{\"commandId\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"Anatomy\"}"))) CreateRequest r) {
        return ok(sets.create(new LearningSetService.Create(r.commandId(),r.name())));
    }
    @PostMapping(value="/sets/{setId}/members",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Add or remove one set member",description="Owner only, with expectedVersion from the current set. present=true adds a SET, exact QUIZ publication or QUESTION; duplicate paths are allowed but member identity is unique. Adding source material and its exact replay require current author access. Child sets must have the same owner; self/ancestor cycles conflict, including concurrent opposite edges. present=false removes only this link and remains usable after source access is lost. Each accepted fresh command advances set and graph versions; exact replay returns its original acknowledgement.")
    @ApiResponse(responseCode="200",description="Original durable acknowledgement.")
    public ResponseEntity<Acknowledgement> changeLearningSetMember(@PathVariable UUID setId,@RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(implementation=ChangeRequest.class),examples=@ExampleObject(name="Request",value="{\"commandId\":\"11111111-1111-4111-8111-111111111111\",\"expectedVersion\":1,\"member\":{\"kind\":\"SET\",\"childSetId\":\"22222222-2222-4222-8222-222222222222\"},\"present\":true}"))) ChangeRequest r) {
        return ok(sets.change(setId,new LearningSetService.Change(r.commandId(),r.expectedVersion(),r.member(),r.present())));
    }
    @PostMapping(value="/sets/{setId}/deletion",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Delete a private set and detach its links",description="Owner only, with the current expectedVersion. Atomically remove incoming/outgoing links and mark this set deleted; child sets, all question schedules, sessions and history remain. Affected parents advance version. GET then returns 404; an exact deletion replay retains the original acknowledgement. No age cleanup or schedule reset.")
    @ApiResponse(responseCode="200",description="Original acknowledgement with deleted=true.")
    public ResponseEntity<Acknowledgement> deleteLearningSet(@PathVariable UUID setId,@RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(implementation=DeleteRequest.class),examples=@ExampleObject(name="Request",value="{\"commandId\":\"11111111-1111-4111-8111-111111111111\",\"expectedVersion\":1}"))) DeleteRequest r) {
        return ok(sets.delete(setId,new LearningSetService.Delete(r.commandId(),r.expectedVersion())));
    }
    @GetMapping("/sets/{setId}")
    @Operation(summary="Read private set metadata",description="Active owner only. No member count or source content; this read consumes no command credit.")
    @ApiResponse(responseCode="200",description="Current set metadata.")
    public ResponseEntity<View> readLearningSet(@PathVariable UUID setId) { return ok(sets.read(setId)); }
    @GetMapping("/sets")
    @Operation(summary="Page private Learning sets",description="Active owner only. Excludes deleted sets. Required limit uses the configured Learning maxBatchSize; ascending unsigned set UUID. next is null at the end. No daily study quota.")
    @ApiResponse(responseCode="200",description="Bounded set page.")
    public ResponseEntity<SetPage> listLearningSets(@RequestParam int limit,@RequestParam(required=false) UUID after) { return ok(sets.list(limit,after)); }
    @GetMapping("/sets/{setId}/members")
    @Operation(summary="Page direct currently authorized members",description="Owner only. Inaccessible source members are omitted without exposing their data/counts. Required limit uses configured maxBatchSize. Stable binary member-key order; next is a nullable opaque cursor bound to this set and graph version. Supply it unchanged as after. Structural changes invalidate it with 409. This is a live view, not an authorization token or snapshot.")
    @ApiResponse(responseCode="200",description="Bounded typed member page.")
    public ResponseEntity<MemberPage> listLearningSetMembers(@PathVariable UUID setId,@RequestParam int limit,@RequestParam(required=false) String after) { return ok(sets.members(setId,limit,after)); }
    @GetMapping("/sets/{setId}/items")
    @Operation(summary="Select existing cycles through a nested set",description="Active owner only. Recursively union currently authorized member-question cycles; each quiz/revision/question appears once, across every path. DUE means dueAt <= server time; UPCOMING means dueAt > server time. Exclude paused/completed custom cycles. Sort by UTC dueAt then unsigned quiz, revision and question UUIDs. Required limit uses configured maxBatchSize. Pass nullable next unchanged as after; cursor binds scope/window/graph version, and graph changes return 409. Schedule changes remain visible between pages. No source bodies/counts, enrollment, review, implicit inheritance or set schedule. The returned cycle is selection provenance only. Starting a nested session remains unavailable pending #667.")
    @ApiResponse(responseCode="200",description="Bounded metadata page, including an explicit empty items array.")
    public ResponseEntity<ItemPage> selectLearningSetItems(@PathVariable UUID setId,@RequestParam Window window,@RequestParam int limit,@RequestParam(required=false) String after) {
        return ok(sets.items(new Scope(setId,null),window,limit,after));
    }
    @GetMapping("/quiz-items")
    @Operation(summary="Select the same cycles through an exact quiz publication",description="Current active author with exact publication access. Uses the same DUE/UPCOMING classification, ordering, cursor, configured batch bound and read-only semantics as nested-set items. No implicit enrollment or review; custom cycles require custom-plans-enabled and custom-reviews-enabled. A later publication never redirects this exact source to another revision.")
    @ApiResponse(responseCode="200",description="Bounded metadata page.")
    public ResponseEntity<ItemPage> selectLearningQuizItems(@Valid @ParameterObject LearningPlanApi.SourceQuery source,
            @RequestParam Window window,@RequestParam int limit,@RequestParam(required=false) String after) {
        return ok(sets.items(new Scope(null,source.domain()),window,limit,after));
    }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
