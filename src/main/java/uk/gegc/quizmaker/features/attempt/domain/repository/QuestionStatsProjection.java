package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.UUID;

public interface QuestionStatsProjection {
    UUID getQuestionId();

    long getTimesAsked();

    long getTimesCorrect();
}
