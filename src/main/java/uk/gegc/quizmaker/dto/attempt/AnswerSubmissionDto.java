package uk.gegc.quizmaker.dto.attempt;

import uk.gegc.quizmaker.dto.question.QuestionDto;

import java.time.Instant;
import java.util.UUID;

public record AnswerSubmissionDto(
        UUID answerId,
        UUID questionId,
        Boolean isCorrect,
        Double score,
        Instant answeredAt,
        QuestionDto nextQuestion
) {
}
