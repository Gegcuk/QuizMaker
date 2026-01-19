package uk.gegc.quizmaker.features.quiz.application.validation;

import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;

public interface QuizImportValidationService {
    void validateQuiz(QuizImportDto quiz, String username, QuizImportOptions options);

    void validateQuestion(QuestionImportDto question, String username);
}
