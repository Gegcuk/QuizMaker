package uk.gegc.quizmaker.service.quiz;

import java.util.UUID;

public interface ModerationService {
    void submitForReview(UUID quizId, UUID userId);
}


