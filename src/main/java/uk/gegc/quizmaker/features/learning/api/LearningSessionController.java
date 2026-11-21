package uk.gegc.quizmaker.features.learning.api;

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
    private static final String SOURCE = "\"source\":{\"kind\":\"QUIZ_REVISION\",\"quizId\":\"11111111-1111-4111-8111-111111111111\",\"quizRevisionId\":\"22222222-2222-4222-8222-222222222222\",\"revisionSchemaVersion\":1}";
    private static final String COMMAND = "{\"commandId\":\"33333333-3333-4333-8333-333333333333\",\"configuration\":{\"strategy\":\"fsrs/v1\",\"version\":1}," + SOURCE;

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
    @Operation(summary = "Start a fixed Learning session", description = "Active quiz author with current publication access. Select currently due questions, optionally shuffle once and save the entire form. An exact retry reuses the original session even when due items or the quiz setting change.")
    @ApiResponse(responseCode = "200", description = "Saved form or explicit EMPTY_COMPLETE.")
    @ApiResponse(responseCode = "429", description = "Shared Learning command limit reached.", headers = @Header(name = "Retry-After", description = "Whole seconds"), content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<View> start(@Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = StartRequest.class),
                    examples = @ExampleObject(value = COMMAND + ",\"limit\":3}"))) StartRequest request) {
        request.configuration().verify();
        return ok(View.from(study.start(new LearningStudyService.Start(request.commandId(), request.source().domain(), request.limit()))));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Resume a fixed Learning session", description = "Session owner with current access to the captured publication. Reads consume no Learning command credit. The saved content and order survive later quiz publication.")
    @ApiResponse(responseCode = "200", description = "Persisted order, progress and currently available actions.")
    public ResponseEntity<View> resume(@PathVariable UUID sessionId) {
        return ok(View.from(study.resume(sessionId)));
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
