package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.api.dto.QuizSeenAcknowledgement;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface QuizFreshnessService {
    /** Internal generation boundary; must join the successful assembly transaction. */
    void registerGeneratedQuiz(UUID quizId);

    Set<UUID> findNewQuizIds(UUID ownerId, Collection<UUID> quizIds);

    /** Identity and ownership are resolved server-side; no caller-selected owner. */
    QuizSeenAcknowledgement acknowledgeSeen(UUID quizId);
}
