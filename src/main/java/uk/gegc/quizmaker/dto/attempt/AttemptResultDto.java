package uk.gegc.quizmaker.dto.attempt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AttemptResultDto(
        UUID attemptId,
        UUID quizId,
        UUID userId,
        Instant startedAt,
        Instant completedAt,
        Double totalScore,
        Integer correctCount,
        Integer totalQuestions,
        List<AnswerSubmissionDto> answers
) {
}
