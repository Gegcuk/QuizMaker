package uk.gegc.quizmaker.features.quiz.api;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> approveQuiz(@PathVariable UUID quizId,
                                            @RequestParam @NotNull UUID moderatorId,
                                            @RequestParam(required = false) String reason) {
        moderationService.approveQuiz(quizId, moderatorId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{quizId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> rejectQuiz(@PathVariable UUID quizId,
                                           @RequestParam @NotNull UUID moderatorId,
                                           @RequestParam String reason) {
        moderationService.rejectQuiz(quizId, moderatorId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{quizId}/unpublish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unpublishQuiz(@PathVariable UUID quizId,
                                              @RequestParam @NotNull UUID moderatorId,
                                              @RequestParam(required = false) String reason) {
        moderationService.unpublishQuiz(quizId, moderatorId, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pending-review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PendingReviewQuizDto>> getPendingReview(@RequestParam UUID orgId) {
        return ResponseEntity.ok(moderationService.getPendingReviewQuizzes(orgId));
    }

    @GetMapping("/{quizId}/audits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<QuizModerationAuditDto>> getAuditTrail(@PathVariable UUID quizId) {
        return ResponseEntity.ok(moderationService.getQuizAuditTrail(quizId));
    }
}


