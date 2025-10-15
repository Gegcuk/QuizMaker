package uk.gegc.quizmaker.features.quiz.infra;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;

@Component
public class ExportMediaTypeResolver {
    public String contentTypeFor(ExportFormat format) {
        return switch (format) {
            case JSON_EDITABLE -> "application/json";
            case XLSX_EDITABLE -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case HTML_PRINT -> "text/html; charset=utf-8";
            case PDF_PRINT -> "application/pdf";
        };
    }

    public String fileExtensionFor(ExportFormat format) {
        return switch (format) {
            case JSON_EDITABLE -> "json";
            case XLSX_EDITABLE -> "xlsx";
            case HTML_PRINT -> "html";
            case PDF_PRINT -> "pdf";
        };
    }
}


