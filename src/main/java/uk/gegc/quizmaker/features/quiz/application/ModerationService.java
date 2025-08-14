package uk.gegc.quizmaker.service.quiz;

import uk.gegc.quizmaker.features.quiz.api.dto.PendingReviewQuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizModerationAuditDto;

import java.util.List;
import java.util.UUID;

public interface ModerationService {
    void submitForReview(UUID quizId, UUID userId);
    void approveQuiz(UUID quizId, UUID moderatorId, String reason);
    void rejectQuiz(UUID quizId, UUID moderatorId, String reason);
    void unpublishQuiz(UUID quizId, UUID moderatorId, String reason);
    List<PendingReviewQuizDto> getPendingReviewQuizzes(UUID orgId);
    List<QuizModerationAuditDto> getQuizAuditTrail(UUID quizId);
}


