package uk.gegc.quizmaker.repository.attempt;

import java.util.UUID;

public interface QuestionStatsProjection {
    UUID getQuestionId();

    long getTimesAsked();

    long getTimesCorrect();
}
