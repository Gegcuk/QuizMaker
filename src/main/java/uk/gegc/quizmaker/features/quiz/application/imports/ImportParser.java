package uk.gegc.quizmaker.features.quiz.application.imports;

import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;

import java.io.InputStream;
import java.util.List;

/**
 * Parser for quiz import files.
 */
public interface ImportParser {
    List<QuizImportDto> parse(InputStream input, QuizImportOptions options);
}
