package uk.gegc.quizmaker.features.result.api.dto;

import java.util.List;
import java.util.UUID;

public record QuizResultSummaryDto(
        UUID quizId,
        Long attemptsCount,
        Double averageScore,
        Double bestScore,
        Double worstScore,
        Double passRate,
        List<QuestionStatsDto> questionStats
) {
}
