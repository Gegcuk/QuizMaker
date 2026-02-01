package uk.gegc.quizmaker.features.repetition.application;

import uk.gegc.quizmaker.features.question.domain.model.Answer;

import java.util.UUID;

public interface RepetitionProcessingService {
    void processAttempt(UUID attemptId);

    void processAnswerTx(Answer answer);
}
