package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.api.dto.PendingReviewQuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizModerationAuditDto;

import java.util.List;
import java.util.UUID;

public interface ModerationService {
    void submitForReview(UUID quizId, UUID userId);
    void approveQuiz(UUID quizId, String moderatorUsername, String reason);
    void rejectQuiz(UUID quizId, String moderatorUsername, String reason);
    void unpublishQuiz(UUID quizId, String moderatorUsername, String reason);
    List<PendingReviewQuizDto> getPendingReviewQuizzes(UUID orgId);
    List<QuizModerationAuditDto> getQuizAuditTrail(UUID quizId);
}


