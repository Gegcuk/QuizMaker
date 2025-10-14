package uk.gegc.quizmaker.features.quiz.application.export;

import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

/**
 * SPI for rendering export payloads into downloadable files.
 */
public interface ExportRenderer {
    boolean supports(ExportFormat format);

    ExportFile render(ExportPayload payload);
}


