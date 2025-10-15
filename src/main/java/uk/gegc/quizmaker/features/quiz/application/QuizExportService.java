package uk.gegc.quizmaker.features.quiz.application;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportFilter;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;

import java.io.OutputStream;

public interface QuizExportService {
    ExportFile export(QuizExportFilter filter, ExportFormat format, PrintOptions printOptions, Authentication authentication);

    void streamExport(QuizExportFilter filter, ExportFormat format, PrintOptions printOptions, OutputStream output, Authentication authentication);
}


