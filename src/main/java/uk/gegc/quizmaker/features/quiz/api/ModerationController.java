package uk.gegc.quizmaker.features.quiz.api;

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
public class ModerationController {

    private final ModerationService moderationService;

    public ModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @PostMapping("/{quizId}/approve")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<Void> approveQuiz(@PathVariable UUID quizId,
                                            @RequestParam(required = false) String reason,
                                            Authentication authentication) {
        moderationService.approveQuiz(quizId, authentication.getName(), reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{quizId}/reject")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<Void> rejectQuiz(@PathVariable UUID quizId,
                                           @RequestParam String reason,
                                           Authentication authentication) {
        moderationService.rejectQuiz(quizId, authentication.getName(), reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{quizId}/unpublish")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<Void> unpublishQuiz(@PathVariable UUID quizId,
                                              @RequestParam(required = false) String reason,
                                              Authentication authentication) {
        moderationService.unpublishQuiz(quizId, authentication.getName(), reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pending-review")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<List<PendingReviewQuizDto>> getPendingReview(@RequestParam UUID orgId) {
        return ResponseEntity.ok(moderationService.getPendingReviewQuizzes(orgId));
    }

    @GetMapping("/{quizId}/audits")
    @RequirePermission(PermissionName.QUIZ_MODERATE)
    public ResponseEntity<List<QuizModerationAuditDto>> getAuditTrail(@PathVariable UUID quizId) {
        return ResponseEntity.ok(moderationService.getQuizAuditTrail(quizId));
    }
}


