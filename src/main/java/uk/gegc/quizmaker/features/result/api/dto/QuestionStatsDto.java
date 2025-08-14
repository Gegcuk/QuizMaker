package uk.gegc.quizmaker.features.result.api.dto;

import java.util.UUID;

public record QuestionStatsDto(
        UUID questionId,    // the ID of the question
        long timesAsked,    // how many completed attempts included this question
        long timesCorrect,  // how many times it was answered correctly
        double correctRate  // (timesCorrect / timesAsked) * 100.0, or 0.0 if timesAsked==0
) {
}
