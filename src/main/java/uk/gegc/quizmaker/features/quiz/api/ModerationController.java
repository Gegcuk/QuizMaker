package uk.gegc.quizmaker.features.quiz.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.quiz.api.dto.PendingReviewQuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizModerationAuditDto;
import uk.gegc.quizmaker.features.quiz.application.ModerationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/quizzes")
@Tag(name = "Quiz Moderation", description = "Quiz moderation and approval workflow for moderators")
@SecurityRequirement(name = "Bearer Authentication")
public class ModerationController {

    private final ModerationService moderationService;

    public ModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @Operation(
            summary = "Approve a quiz",
            description = "Approves a quiz for publication. Requires QUIZ_MODERATE permission."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quiz approved successfully"),
            @ApiResponse(responseCode = "403", description = "Missing QUIZ_MODERATE permission"),
            @ApiResponse(responseCode = "404", description = "Quiz not found")
    })
    @PostMapping("/{quizId}/approve")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<Void> approveQuiz(
            @Parameter(description = "Quiz UUID to approve", required = true) @PathVariable UUID quizId,
            @Parameter(description = "Optional reason for approval") @RequestParam(required = false) String reason,
            Authentication authentication) {
        moderationService.approveQuiz(quizId, authentication.getName(), reason);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Reject a quiz",
            description = "Rejects a quiz from publication. Requires QUIZ_MODERATE permission."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quiz rejected successfully"),
            @ApiResponse(responseCode = "400", description = "Reason is required"),
            @ApiResponse(responseCode = "403", description = "Missing QUIZ_MODERATE permission"),
            @ApiResponse(responseCode = "404", description = "Quiz not found")
    })
    @PostMapping("/{quizId}/reject")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<Void> rejectQuiz(
            @Parameter(description = "Quiz UUID to reject", required = true) @PathVariable UUID quizId,
            @Parameter(description = "Reason for rejection", required = true) @RequestParam String reason,
            Authentication authentication) {
        moderationService.rejectQuiz(quizId, authentication.getName(), reason);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Unpublish a quiz",
            description = "Unpublishes a previously published quiz. Requires QUIZ_MODERATE permission."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quiz unpublished successfully"),
            @ApiResponse(responseCode = "403", description = "Missing QUIZ_MODERATE permission"),
            @ApiResponse(responseCode = "404", description = "Quiz not found")
    })
    @PostMapping("/{quizId}/unpublish")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<Void> unpublishQuiz(
            @Parameter(description = "Quiz UUID to unpublish", required = true) @PathVariable UUID quizId,
            @Parameter(description = "Optional reason for unpublishing") @RequestParam(required = false) String reason,
            Authentication authentication) {
        moderationService.unpublishQuiz(quizId, authentication.getName(), reason);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get pending review quizzes",
            description = "Returns all quizzes pending moderation review for an organization. Requires QUIZ_MODERATE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of pending review quizzes",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PendingReviewQuizDto.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Missing QUIZ_MODERATE permission")
    })
    @GetMapping("/pending-review")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<List<PendingReviewQuizDto>> getPendingReview(
            @Parameter(description = "Organization UUID", required = true) @RequestParam UUID orgId) {
        return ResponseEntity.ok(moderationService.getPendingReviewQuizzes(orgId));
    }

    @Operation(
            summary = "Get moderation audit trail",
            description = "Returns the moderation audit history for a quiz. Requires QUIZ_MODERATE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Moderation audit trail",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = QuizModerationAuditDto.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Missing QUIZ_MODERATE permission"),
            @ApiResponse(responseCode = "404", description = "Quiz not found")
    })
    @GetMapping("/{quizId}/audits")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<List<QuizModerationAuditDto>> getAuditTrail(
            @Parameter(description = "Quiz UUID", required = true) @PathVariable UUID quizId) {
        return ResponseEntity.ok(moderationService.getQuizAuditTrail(quizId));
    }
}


