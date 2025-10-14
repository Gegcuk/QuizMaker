package uk.gegc.quizmaker.features.quiz.domain.model.export;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.UUID;

/**
 * Represents a single entry in an answer key.
 * Contains normalized answer data extracted from question content.
 */
public record AnswerKeyEntry(
    int index,
    UUID questionId,
    QuestionType type,
    JsonNode normalizedAnswer
) {
    public AnswerKeyEntry {
        if (index < 0) {
            throw new IllegalArgumentException("Index cannot be negative");
        }
        if (questionId == null) {
            throw new IllegalArgumentException("Question ID cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("Question type cannot be null");
        }
        if (normalizedAnswer == null) {
            throw new IllegalArgumentException("Normalized answer cannot be null");
        }
    }
}

