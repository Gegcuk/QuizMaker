package uk.gegc.quizmaker.features.quiz.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSeenAcknowledgement;
import uk.gegc.quizmaker.features.quiz.application.QuizFreshnessService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
@Tag(name = "Quizzes")
@SecurityRequirement(name = "bearerAuth")
public class QuizFreshnessController {
    private final QuizFreshnessService freshnessService;

    @Operation(summary = "Acknowledge an owned quiz as seen", description = """
            Requires authentication, QUIZ_READ, and strict creator ownership, including for moderators.
            Called explicitly after hover, keyboard focus, or a phone tap; list GET never acknowledges.
            Idempotent and monotonic: repeated/concurrent requests return isNew=false, including for
            historical, manual, and imported quizzes. No request body or owner ID is accepted.
            A failed transaction retains the prior state. Retry on a later explicit interaction;
            no background or persistent offline queue is required. Reconcile only the current
            account's owned-list cache from the confirmed response; ignore stale/late account responses.
            Existing web/iOS clients may ignore the additive owner-list field and this operation.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seen state committed", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = QuizSeenAcknowledgement.class),
                    examples = @ExampleObject(name = "seen", value = "{\"quizId\":\"d290f1ee-6c54-4b01-90e6-d701748f0851\",\"isNew\":false}"))),
            @ApiResponse(responseCode = "400", description = "Malformed quiz UUID", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "QUIZ_READ permission required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz missing, deleted, or not owned", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Write failed; no acknowledgement committed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{quizId}/seen")
    public ResponseEntity<QuizSeenAcknowledgement> acknowledgeSeen(@PathVariable UUID quizId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(freshnessService.acknowledgeSeen(quizId));
    }
}
