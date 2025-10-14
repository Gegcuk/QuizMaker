package uk.gegc.quizmaker.features.quiz.application.export.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class JsonExportRenderer implements ExportRenderer {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ExportFormat format) {
        return format == ExportFormat.JSON_EDITABLE;
    }

    @Override
    public ExportFile render(ExportPayload payload) {
        try {
            // Deterministic filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            String filename = "quizzes_export_" + timestamp + ".json";

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return new ExportFile(
                    filename,
                    "application/json",
                    () -> new ByteArrayInputStream(bytes),
                    bytes.length
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to render JSON export", e);
        }
    }
}


