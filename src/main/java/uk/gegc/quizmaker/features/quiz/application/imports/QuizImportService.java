package uk.gegc.quizmaker.features.quiz.application.imports;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportSummaryDto;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;

import java.io.InputStream;

public interface QuizImportService {
    ImportSummaryDto importQuizzes(InputStream input,
                                   ExportFormat format,
                                   QuizImportOptions options,
                                   Authentication authentication);
}
