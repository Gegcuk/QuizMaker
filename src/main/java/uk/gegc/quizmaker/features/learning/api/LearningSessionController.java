package uk.gegc.quizmaker.features.learning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.learning.application.LearningStudyService;
import uk.gegc.quizmaker.features.learning.domain.model.LearningReview;
import java.util.UUID;
import static uk.gegc.quizmaker.features.learning.api.LearningSessionApi.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quizmaker.learning.sessions-enabled", havingValue = "true")
@RequestMapping(value = "/api/v1/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid request, duplicate question or invalid size.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Authentication required.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Unknown or inaccessible session, source or member.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Conflicting command identity, stale capture, or lock timeout. A lock timeout uses data-conflict and permits retrying the original request.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "422", description = "Unsupported configuration or content contract.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Storage or batch configuration unavailable; retryable database conflict.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "500", description = "Inconsistent retained evidence or incompatible transaction.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class LearningSessionController {
    private final LearningStudyService study;
    private final ObjectMapper json;
    private static final String SOURCE = "\"source\":{\"kind\":\"QUIZ_REVISION\",\"quizId\":\"11111111-1111-4111-8111-111111111111\",\"quizRevisionId\":\"22222222-2222-4222-8222-222222222222\",\"revisionSchemaVersion\":1}";
    private static final String COMMAND = "{\"commandId\":\"33333333-3333-4333-8333-333333333333\",\"configuration\":{\"strategy\":\"fsrs/v1\",\"version\":1}," + SOURCE;
    private static final String RESPONSE = """
            {"commandId":"33333333-3333-4333-8333-333333333333","expectedSessionVersion":1,
             "sourceItemId":"44444444-4444-4444-8444-444444444444","rating":"GOOD",
             "response":{"type":"TRUE_FALSE","answer":true}}
            """;

    @PostMapping("/enrollments")
    @Operation(summary = "Enroll questions for Learning", description = "Online, active author only, with current publication-history access. Prepare the whole publication once. The ordered batch commits together. Use the same commandId and input after losing a response.")
    @ApiResponse(responseCode = "200", description = "Original enrolled cycle identities, including exact replay.")
    @ApiResponse(responseCode = "429", description = "Shared Learning command limit reached.", headers = @Header(name = "Retry-After", description = "Whole seconds"), content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Enrolled> enroll(@Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = EnrollRequest.class),
                    examples = @ExampleObject(value = COMMAND + ",\"questionRevisionIds\":[\"44444444-4444-4444-8444-444444444444\"]}"))) EnrollRequest request) {
        request.configuration().verify();
        return ok(Enrolled.from(study.enroll(new LearningStudyService.Enroll(request.commandId(), request.source().domain(), request.questionRevisionIds()))));
    }

    @PostMapping("/sessions")
    @Operation(summary = "Start a fixed Learning session", description = "Active quiz author with current publication access. By default select currently due items and optionally shuffle once. With custom-plans-enabled and the separate custom-reviews-enabled opt-in, selection also includes enrolled custom/v1 cycles, preserving each captured immutable plan. Paused/completed custom cycles are excluded from due selection. With limit 1 and explicit selection, use one already enrolled question for EARLY review or PRACTICE. Exact retry returns the original start snapshot even if due items, progress or the quiz setting change.")
    @ApiResponse(responseCode = "200", description = "Saved form or explicit EMPTY_COMPLETE.")
    @ApiResponse(responseCode = "429", description = "Shared Learning command limit reached.", headers = @Header(name = "Retry-After", description = "Whole seconds"), content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<View> start(@Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = StartRequest.class),
                    examples = {@ExampleObject(name = "Due", value = COMMAND + ",\"limit\":3}"),
                            @ExampleObject(name = "Early", value = COMMAND + ",\"limit\":1,\"selection\":{\"mode\":\"EARLY\",\"questionRevisionId\":\"44444444-4444-4444-8444-444444444444\"}}")})) StartRequest request) {
        request.configuration().verify();
        return ok(View.from(study.start(request.domain())));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Resume a fixed Learning session", description = "Session owner with current access to the captured publication. Reads consume no Learning command credit. The saved content and order survive later quiz publication. An active session is returned only while its next response can pass the schedule checks: its own item, and before the final answer every captured item. An unsupported or damaged retained schedule returns 422 instead of offering RESPOND. Completed and superseded sessions remain readable.")
    @ApiResponse(responseCode = "200", description = "Persisted order, progress and currently available actions. A SUPERSEDED session has no next item or response action and retains its accepted progress.")
    public ResponseEntity<View> resume(@PathVariable UUID sessionId) {
        return ok(View.from(study.resume(sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/responses")
    @Operation(summary = "Answer and rate the next Learning item", description = "Online, session owner with current publication access. Submit the next unanswered item in saved order and its expected session version. A due or early review advances the captured FSRS/custom strategy once and ends other scheduled sessions that still need that step. Extra practice records progress without changing a schedule or ending other sessions. The last answer completes the session. Retry the exact command after a lost response. Learning responses do not create assessment results.")
    @ApiResponse(responseCode = "200", description = "Original acknowledgement with the next session item and, if advanced, the next scheduled review.")
    @ApiResponse(responseCode = "409", description = "Changed command, stale item or state, or a completed or superseded session. An exact accepted replay still returns its original acknowledgement.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Shared Learning command limit reached.", headers = @Header(name = "Retry-After", description = "Whole seconds"), content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Acknowledgement> respond(@PathVariable UUID sessionId, @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = RespondRequest.class),
                    examples = @ExampleObject(value = RESPONSE))) RespondRequest request) {
        return ok(Acknowledgement.from(study.respond(new LearningReview.Command(sessionId, request.commandId(),
                request.expectedSessionVersion(), request.sourceItemId(), request.rating(), json.valueToTree(request.response())))));
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
