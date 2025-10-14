package uk.gegc.quizmaker.features.quiz.application.export.impl;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class XlsxExportRenderer implements ExportRenderer {

    @Override
    public boolean supports(ExportFormat format) {
        return format == ExportFormat.XLSX_EDITABLE;
    }

    @Override
    public ExportFile render(ExportPayload payload) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Quizzes");
            int rowIdx = 0;

            // Header
            Row header = sheet.createRow(rowIdx++);
            header.createCell(0).setCellValue("Quiz ID");
            header.createCell(1).setCellValue("Title");
            header.createCell(2).setCellValue("Description");
            header.createCell(3).setCellValue("Visibility");
            header.createCell(4).setCellValue("Difficulty");
            header.createCell(5).setCellValue("Estimated Time");
            header.createCell(6).setCellValue("Tags");
            header.createCell(7).setCellValue("Category");
            header.createCell(8).setCellValue("Creator ID");

            for (var quiz : payload.quizzes()) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(quiz.id() != null ? quiz.id().toString() : "");
                row.createCell(1).setCellValue(quiz.title() != null ? quiz.title() : "");
                row.createCell(2).setCellValue(quiz.description() != null ? quiz.description() : "");
                row.createCell(3).setCellValue(quiz.visibility() != null ? quiz.visibility().name() : "");
                row.createCell(4).setCellValue(quiz.difficulty() != null ? quiz.difficulty().name() : "");
                row.createCell(5).setCellValue(quiz.estimatedTime() != null ? quiz.estimatedTime() : 0);
                row.createCell(6).setCellValue(quiz.tags() != null ? String.join(",", quiz.tags()) : "");
                row.createCell(7).setCellValue(quiz.category() != null ? quiz.category() : "");
                row.createCell(8).setCellValue(quiz.creatorId() != null ? quiz.creatorId().toString() : "");
            }

            // TODO: could add a second sheet for questions per quiz for full round-trip

            // Autosize columns
            for (int i = 0; i <= 8; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            byte[] bytes = baos.toByteArray();

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            String filename = "quizzes_export_" + timestamp + ".xlsx";
            return new ExportFile(
                    filename,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    () -> new ByteArrayInputStream(bytes),
                    bytes.length
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to render XLSX export", e);
        }
    }
}


