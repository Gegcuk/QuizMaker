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
            // Sheet 1: Quizzes
            createQuizzesSheet(workbook, payload);
            
            // Sheet 2: Questions
            createQuestionsSheet(workbook, payload);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            byte[] bytes = baos.toByteArray();

            String filename = payload.filenamePrefix() + ".xlsx";
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

    private void createQuizzesSheet(Workbook workbook, ExportPayload payload) {
        Sheet sheet = workbook.createSheet("Quizzes");
        int rowIdx = 0;

        // Header
        Row header = sheet.createRow(rowIdx++);
        header.createCell(0).setCellValue("Quiz ID");
        header.createCell(1).setCellValue("Title");
        header.createCell(2).setCellValue("Description");
        header.createCell(3).setCellValue("Visibility");
        header.createCell(4).setCellValue("Difficulty");
        header.createCell(5).setCellValue("Estimated Time (min)");
        header.createCell(6).setCellValue("Tags");
        header.createCell(7).setCellValue("Category");
        header.createCell(8).setCellValue("Creator ID");
        header.createCell(9).setCellValue("Created At");
        header.createCell(10).setCellValue("Updated At");

        // Data rows
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
            row.createCell(9).setCellValue(quiz.createdAt() != null ? quiz.createdAt().toString() : "");
            row.createCell(10).setCellValue(quiz.updatedAt() != null ? quiz.updatedAt().toString() : "");
        }

        // Autosize columns
        for (int i = 0; i <= 10; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createQuestionsSheet(Workbook workbook, ExportPayload payload) {
        Sheet sheet = workbook.createSheet("Questions");
        int rowIdx = 0;

        // Header - common columns + type-specific columns
        Row header = sheet.createRow(rowIdx++);
        int colIdx = 0;
        header.createCell(colIdx++).setCellValue("Question ID");
        header.createCell(colIdx++).setCellValue("Quiz ID");
        header.createCell(colIdx++).setCellValue("Type");
        header.createCell(colIdx++).setCellValue("Difficulty");
        header.createCell(colIdx++).setCellValue("Question Text");
        header.createCell(colIdx++).setCellValue("Hint");
        header.createCell(colIdx++).setCellValue("Explanation");
        header.createCell(colIdx++).setCellValue("Attachment URL");
        
        // Type-specific columns (common patterns)
        header.createCell(colIdx++).setCellValue("Answer/Correct"); // For TRUE_FALSE, OPEN
        header.createCell(colIdx++).setCellValue("Option 1");
        header.createCell(colIdx++).setCellValue("Option 1 Correct");
        header.createCell(colIdx++).setCellValue("Option 2");
        header.createCell(colIdx++).setCellValue("Option 2 Correct");
        header.createCell(colIdx++).setCellValue("Option 3");
        header.createCell(colIdx++).setCellValue("Option 3 Correct");
        header.createCell(colIdx++).setCellValue("Option 4");
        header.createCell(colIdx++).setCellValue("Option 4 Correct");
        header.createCell(colIdx++).setCellValue("Option 5");
        header.createCell(colIdx++).setCellValue("Option 5 Correct");
        header.createCell(colIdx++).setCellValue("Gap 1");
        header.createCell(colIdx++).setCellValue("Gap 2");
        header.createCell(colIdx++).setCellValue("Gap 3");
        header.createCell(colIdx++).setCellValue("Gap 4");
        header.createCell(colIdx++).setCellValue("Gap 5");
        header.createCell(colIdx++).setCellValue("Raw Content (JSON)"); // Fallback for complex types

        // Data rows
        for (var quiz : payload.quizzes()) {
            for (var question : quiz.questions()) {
                Row row = sheet.createRow(rowIdx++);
                colIdx = 0;
                
                // Common fields
                row.createCell(colIdx++).setCellValue(question.id() != null ? question.id().toString() : "");
                row.createCell(colIdx++).setCellValue(quiz.id() != null ? quiz.id().toString() : "");
                row.createCell(colIdx++).setCellValue(question.type() != null ? question.type().name() : "");
                row.createCell(colIdx++).setCellValue(question.difficulty() != null ? question.difficulty().name() : "");
                row.createCell(colIdx++).setCellValue(question.questionText() != null ? question.questionText() : "");
                row.createCell(colIdx++).setCellValue(question.hint() != null ? question.hint() : "");
                row.createCell(colIdx++).setCellValue(question.explanation() != null ? question.explanation() : "");
                row.createCell(colIdx++).setCellValue(question.attachmentUrl() != null ? question.attachmentUrl() : "");
                
                // Parse type-specific content
                parseContentToColumns(row, colIdx, question);
            }
        }

        // Autosize columns
        for (int i = 0; i < header.getLastCellNum(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void parseContentToColumns(Row row, int startCol, uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto question) {
        var content = question.content();
        int colIdx = startCol;
        
        if (content == null || content.isNull()) {
            row.createCell(colIdx + 16).setCellValue("{}"); // Raw content column
            return;
        }

        switch (question.type()) {
            case TRUE_FALSE -> {
                // Answer/Correct column
                if (content.has("answer")) {
                    row.createCell(colIdx).setCellValue(content.get("answer").asBoolean() ? "True" : "False");
                }
            }
            case OPEN -> {
                // Answer/Correct column
                if (content.has("answer")) {
                    row.createCell(colIdx).setCellValue(content.get("answer").asText());
                }
            }
            case MCQ_SINGLE, MCQ_MULTI -> {
                // Parse options into Option columns
                if (content.has("options")) {
                    var options = content.get("options");
                    int optionIdx = 0;
                    for (var option : options) {
                        if (optionIdx >= 5) break; // Max 5 options in columns
                        
                        int optionCol = colIdx + 1 + (optionIdx * 2); // Skip Answer column, 2 cols per option
                        String text = option.has("text") ? option.get("text").asText() : "";
                        boolean correct = option.has("correct") && option.get("correct").asBoolean();
                        
                        row.createCell(optionCol).setCellValue(text);
                        row.createCell(optionCol + 1).setCellValue(correct ? "YES" : "NO");
                        optionIdx++;
                    }
                }
            }
            case FILL_GAP -> {
                // Parse gaps into Gap columns
                if (content.has("gaps")) {
                    var gaps = content.get("gaps");
                    int gapIdx = 0;
                    for (var gap : gaps) {
                        if (gapIdx >= 5) break; // Max 5 gaps in columns
                        
                        int gapCol = colIdx + 11 + gapIdx; // After options columns
                        String answer = gap.has("answer") ? gap.get("answer").asText() : "";
                        row.createCell(gapCol).setCellValue(answer);
                        gapIdx++;
                    }
                }
            }
            case ORDERING, MATCHING, HOTSPOT, COMPLIANCE -> {
                // These are complex - keep as JSON in Raw Content column
                row.createCell(colIdx + 16).setCellValue(content.toString());
            }
        }
        
        // Always include raw JSON for safety/import
        row.createCell(colIdx + 16).setCellValue(content.toString());
    }
}


