package uk.gegc.quizmaker.features.quiz.application.imports;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.quiz.application.imports.impl.JsonImportParser;
import uk.gegc.quizmaker.features.quiz.application.imports.impl.XlsxImportParser;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;

@Component
@RequiredArgsConstructor
public class ImportParserFactory {

    private final JsonImportParser jsonImportParser;
    private final XlsxImportParser xlsxImportParser;

    public ImportParser getParser(ExportFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("Import format is required");
        }
        return switch (format) {
            case JSON_EDITABLE -> jsonImportParser;
            case XLSX_EDITABLE -> xlsxImportParser;
            default -> throw new UnsupportedFormatException("Unsupported import format: " + format);
        };
    }
}
