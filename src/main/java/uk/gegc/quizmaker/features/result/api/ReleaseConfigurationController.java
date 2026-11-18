package uk.gegc.quizmaker.features.result.api;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.result.api.dto.ReleaseConfigurationDtos.*;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException;

@RestController
@RequestMapping(value = "/api/v1/quizzes/{quizId}/revisions/{revisionId}/release-configurations", produces = "application/json")
@Tag(name = "Quizzes")
@ConditionalOnProperty(name = "quizmaker.results.release-configuration-enabled", havingValue = "true")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Malformed or missing input", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Absent or inaccessible quiz/revision/configuration; current author authority is required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Stale expectedVersion or changed command replay", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Unsupported version or invalid explicit rule/time", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Invalid stored configuration evidence", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "503", description = "Storage unavailable or unknown commit; reconcile using identical command", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))) })
public class ReleaseConfigurationController {
    private final ReleaseConfigurationService configurations;
    public ReleaseConfigurationController(ReleaseConfigurationService configurations) { this.configurations = configurations; }
    private static final String AUTHORITY = "Requires current QUIZ_UPDATE and quiz ownership, or the established QUIZ_MODERATE/QUIZ_ADMIN exception. Public-quiz publication retains moderation requirements. Never grants manual-release permission. Online only; no pagination, filtering or sorting. Disabled by default. Existing web/iOS contracts and participant disclosure remain unchanged. ";
    private static final String EXAMPLE = """
            {"version":1,"triggers":["SCHEDULED"],"categories":["TOTAL_SCORE","PASS_FAIL"],"schedule":{"localTime":"2026-12-04T17:00:00","zoneId":"Europe/London","offset":"Z"}}
            """;
    @PostMapping(value = "/preview", consumes = "application/json")
    @Operation(operationId = "previewReleaseConfiguration", summary = "Validate an explicit result-release rule", description = AUTHORITY + "Pure preview after authorization; writes nothing and reserves no command.")
    @ApiResponse(responseCode = "200", description = "Validated configuration", content = @Content(schema = @Schema(implementation = RuleView.class)))
    public ResponseEntity<RuleView> preview(@PathVariable UUID quizId, @PathVariable UUID revisionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = RuleRequest.class), examples = @ExampleObject(value = EXAMPLE)))
            @Valid @RequestBody RuleRequest request, BindingResult validation) {
        valid(request, validation);
        return ok(RuleView.from(configurations.preview(quizId, revisionId, request.rule())));
    }
    @PostMapping(consumes = "application/json")
    @Operation(operationId = "publishReleaseConfiguration", summary = "Publish an immutable release configuration", description = AUTHORITY + "One transaction stores version, current pointer and actor/command evidence. Identical replay rechecks current authority and returns the original version even after later publications; changed input conflicts. No release worker, certificate or notification is executed.")
    @ApiResponse(responseCode = "200", description = "Published configuration or original replay", content = @Content(schema = @Schema(implementation = PublicationView.class)))
    public ResponseEntity<PublicationView> publish(@PathVariable UUID quizId, @PathVariable UUID revisionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = PublishRequest.class), examples = @ExampleObject(value = """
            {"commandId":"00000000-0000-0000-0000-000000000001","expectedVersion":0,"rule":{"version":1,"triggers":["MANUAL"]}}
            """))) @Valid @RequestBody PublishRequest request, BindingResult validation) {
        valid(request, validation);
        return ok(PublicationView.from(configurations.publish(new ReleaseConfigurationService.Command(quizId, revisionId,
                request.commandId(), request.expectedVersion(), request.rule().rule()))));
    }
    @GetMapping
    @Operation(operationId = "currentReleaseConfiguration", summary = "Read the authoring configuration pointer", description = AUTHORITY + "404 means no accessible current configuration; it never chooses immediate formal release.")
    @ApiResponse(responseCode = "200", description = "Current immutable configuration", content = @Content(schema = @Schema(implementation = PublicationView.class)))
    public ResponseEntity<PublicationView> current(@PathVariable UUID quizId, @PathVariable UUID revisionId) {
        return ok(PublicationView.from(configurations.current(quizId, revisionId)));
    }
    @GetMapping("/{configurationId}")
    @Operation(operationId = "readReleaseConfiguration", summary = "Read a captured configuration version", description = AUTHORITY + "The exact quiz/revision/configuration binding is required; later publication cannot reinterpret this version.")
    @ApiResponse(responseCode = "200", description = "Captured immutable configuration", content = @Content(schema = @Schema(implementation = PublicationView.class)))
    public ResponseEntity<PublicationView> read(@PathVariable UUID quizId, @PathVariable UUID revisionId, @PathVariable UUID configurationId) {
        return ok(PublicationView.from(configurations.read(quizId, revisionId, configurationId)));
    }
    private static void valid(Object request, BindingResult validation) {
        if (request == null || validation.hasErrors()) throw new ReleaseConfigurationException(ReleaseConfigurationException.Reason.INVALID_INPUT);
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
