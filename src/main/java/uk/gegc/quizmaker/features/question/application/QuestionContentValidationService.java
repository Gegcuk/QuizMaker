package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/**
 * Applies the authoritative runtime content rules for a question type.
 */
public interface QuestionContentValidationService {

    /**
     * Validates persisted question content using the same rules applied by runtime handlers.
     *
     * @throws uk.gegc.quizmaker.shared.exception.ValidationException when content is invalid
     * @throws UnsupportedOperationException when no handler supports the question type
     */
    void validateContent(QuestionType type, JsonNode content);
}
