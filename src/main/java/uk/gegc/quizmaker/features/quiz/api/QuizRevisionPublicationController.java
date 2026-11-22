package uk.gegc.quizmaker.features.quiz.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.quiz.api.dto.PublishQuizRevisionRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizRevisionPublicationResponse;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quizzes/{quizId}/publications")
@Tag(name = "Quizzes")
@SecurityRequirement(name = "bearerAuth")
public class QuizRevisionPublicationController {
    private static final String FIRST_COMMAND = """
            {"commandId":"11111111-1111-4111-8111-111111111111","expectedPublicationVersion":0,
             "questionOrder":["22222222-2222-4222-8222-222222222222"]}
            """;
    private static final String FIRST_RESULT = """
            {"quizId":"33333333-3333-4333-8333-333333333333","quizRevisionId":"44444444-4444-4444-8444-444444444444",
             "revisionSchemaVersion":1,"publicationVersion":1,"questionRevisionIds":["55555555-5555-4555-8555-555555555555"]}
            """;
    private final QuizRevisionPublicationService publications;

    public QuizRevisionPublicationController(QuizRevisionPublicationService publications) {
        this.publications = publications;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "publishQuizRevision", summary = "Publish an immutable quiz revision", description = """
            Online author action. An active owner needs QUIZ_UPDATE. QUIZ_MODERATE or QUIZ_ADMIN can publish across ownership
            and is required for a public quiz. Each question needs recorded source ownership. Reuse of another author's
            recorded source requires QUIZ_MODERATE, QUIZ_ADMIN or QUESTION_ADMIN. Send one JSON document with the entire
            draft in the supplied order.
            Resend the same commandId and inputs after a lost response or QUIZ_PUBLICATION_UNKNOWN_OUTCOME. An exact replay
            rechecks current quiz authority and returns the original acknowledgement, even after a later publication.
            A stale counter conflicts for changed content. Identical current content may reuse its existing publication.
            This additive operation leaves draft saves, legacy training and existing client contracts in place.
            """)
    @ApiResponse(responseCode = "200", description = "Publication acknowledged or original acknowledgement replayed",
            content = @Content(schema = @Schema(implementation = QuizRevisionPublicationResponse.class),
                    examples = @ExampleObject(name = "First publication", value = FIRST_RESULT)))
    @ApiResponse(responseCode = "400", description = "Malformed JSON or invalid command fields",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The quiz or required question-source authority is unavailable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Stale publication counter or conflicting reuse of the command ID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "The command requires an application/json request",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The order does not cover the draft or stored content cannot be published",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Internal publication contract or integrity failure",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Retained media or persistence unavailable, or commit outcome unknown",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<QuizRevisionPublicationResponse> publish(
            @PathVariable UUID quizId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = PublishQuizRevisionRequest.class),
                    examples = @ExampleObject(name = "Publish an unpublished draft", value = FIRST_COMMAND)))
            @RequestBody PublishQuizRevisionRequest request) {
        var result = publications.publish(new QuizPublication.Command(1, quizId, request.commandId(),
                request.expectedPublicationVersion(), request.questionOrder()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(QuizRevisionPublicationResponse.from(result));
    }
}
