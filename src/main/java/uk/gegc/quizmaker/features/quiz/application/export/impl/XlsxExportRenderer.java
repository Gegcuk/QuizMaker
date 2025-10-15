package uk.gegc.quizmaker.features.quiz.application.export.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

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
            // Sheet 1: Quizzes metadata
            createQuizzesSheet(workbook, payload);
            
            // Group questions by type
            Map<QuestionType, List<QuestionWithQuizId>> questionsByType = groupQuestionsByType(payload);
            
            // Create a sheet for each question type
            for (Map.Entry<QuestionType, List<QuestionWithQuizId>> entry : questionsByType.entrySet()) {
                createQuestionTypeSheet(workbook, entry.getKey(), entry.getValue());
            }

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

    /**
     * Helper record to associate questions with their quiz ID
     */
    private record QuestionWithQuizId(UUID quizId, QuestionExportDto question) {}
    
    /**
     * Group all questions by their type across all quizzes
     */
    private Map<QuestionType, List<QuestionWithQuizId>> groupQuestionsByType(ExportPayload payload) {
        Map<QuestionType, List<QuestionWithQuizId>> grouped = new LinkedHashMap<>();
        
        for (var quiz : payload.quizzes()) {
            for (var question : quiz.questions()) {
                QuestionType type = question.type();
                grouped.computeIfAbsent(type, k -> new ArrayList<>())
                       .add(new QuestionWithQuizId(quiz.id(), question));
            }
        }
        
        return grouped;
    }
    
    /**
     * Create a sheet for a specific question type with appropriate columns
     */
    private void createQuestionTypeSheet(Workbook workbook, QuestionType type, 
                                        List<QuestionWithQuizId> questions) {
        Sheet sheet = workbook.createSheet(type.name());
        
        // Create header and data rows based on question type
        switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> createMcqSheet(sheet, questions, type);
            case TRUE_FALSE -> createTrueFalseSheet(sheet, questions);
            case OPEN -> createOpenSheet(sheet, questions);
            case FILL_GAP -> createFillGapSheet(sheet, questions);
            case ORDERING -> createOrderingSheet(sheet, questions);
            case MATCHING -> createMatchingSheet(sheet, questions);
            case COMPLIANCE -> createComplianceSheet(sheet, questions);
            case HOTSPOT -> createHotspotSheet(sheet, questions);
        }
    }
    
    /**
     * Create common header columns (before type-specific content)
     */
    private int createCommonHeadersBeforeContent(Row header) {
        int colIdx = 0;
        header.createCell(colIdx++).setCellValue("Question ID");
        header.createCell(colIdx++).setCellValue("Quiz ID");
        header.createCell(colIdx++).setCellValue("Difficulty");
        header.createCell(colIdx++).setCellValue("Question Text");
        return colIdx;
    }
    
    /**
     * Create common header columns (after type-specific content)
     */
    private int createCommonHeadersAfterContent(Row header, int startCol) {
        int colIdx = startCol;
        header.createCell(colIdx++).setCellValue("Hint");
        header.createCell(colIdx++).setCellValue("Explanation");
        header.createCell(colIdx++).setCellValue("Attachment URL");
        return colIdx;
    }
    
    /**
     * Fill common data columns (before type-specific content)
     */
    private int fillCommonDataBeforeContent(Row row, QuestionWithQuizId qwq) {
        QuestionExportDto q = qwq.question();
        int colIdx = 0;
        row.createCell(colIdx++).setCellValue(q.id() != null ? q.id().toString() : "");
        row.createCell(colIdx++).setCellValue(qwq.quizId() != null ? qwq.quizId().toString() : "");
        row.createCell(colIdx++).setCellValue(q.difficulty() != null ? q.difficulty().name() : "");
        row.createCell(colIdx++).setCellValue(q.questionText() != null ? q.questionText() : "");
        return colIdx;
    }
    
    /**
     * Fill common data columns (after type-specific content)
     */
    private int fillCommonDataAfterContent(Row row, QuestionWithQuizId qwq, int startCol) {
        QuestionExportDto q = qwq.question();
        int colIdx = startCol;
        row.createCell(colIdx++).setCellValue(q.hint() != null ? q.hint() : "");
        row.createCell(colIdx++).setCellValue(q.explanation() != null ? q.explanation() : "");
        row.createCell(colIdx++).setCellValue(q.attachmentUrl() != null ? q.attachmentUrl() : "");
        return colIdx;
    }

    private void createMcqSheet(Sheet sheet, List<QuestionWithQuizId> questions, QuestionType type) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Options + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        
        // MCQ-specific columns - up to 6 options
        for (int i = 1; i <= 6; i++) {
            header.createCell(colIdx++).setCellValue("Option " + i);
            header.createCell(colIdx++).setCellValue("Option " + i + " Correct");
        }
        
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Options
            JsonNode content = qwq.question().content();
            if (content != null && content.has("options")) {
                var options = content.get("options");
                int optionIdx = 0;
                for (JsonNode option : options) {
                    if (optionIdx >= 6) break;
                    
                    String text = option.has("text") ? option.get("text").asText() : "";
                    boolean correct = option.has("correct") ? option.get("correct").asBoolean() : false;
                    
                    row.createCell(colIdx++).setCellValue(text);
                    row.createCell(colIdx++).setCellValue(correct ? "YES" : "NO");
                    optionIdx++;
                }
                // Fill empty cells for unused option slots
                for (int i = optionIdx; i < 6; i++) {
                    row.createCell(colIdx++).setCellValue("");
                    row.createCell(colIdx++).setCellValue("");
                }
            } else {
                // Fill all option columns as empty
                for (int i = 0; i < 6; i++) {
                    row.createCell(colIdx++).setCellValue("");
                    row.createCell(colIdx++).setCellValue("");
                }
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createTrueFalseSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Answer + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        header.createCell(colIdx++).setCellValue("Correct Answer");
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Answer field
            JsonNode content = qwq.question().content();
            if (content != null && content.has("answer")) {
                boolean answer = content.get("answer").asBoolean();
                row.createCell(colIdx++).setCellValue(answer ? "True" : "False");
            } else {
                row.createCell(colIdx++).setCellValue(""); // Empty if missing
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize all columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createOpenSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Sample Answer + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        header.createCell(colIdx++).setCellValue("Sample Answer");
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Sample answer
            JsonNode content = qwq.question().content();
            if (content != null && content.has("answer")) {
                row.createCell(colIdx++).setCellValue(content.get("answer").asText());
            } else {
                row.createCell(colIdx++).setCellValue("");
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createFillGapSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Gaps + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        
        // Gap columns
        for (int i = 1; i <= 10; i++) {
            header.createCell(colIdx++).setCellValue("Gap " + i + " Answer");
        }
        
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Gaps
            JsonNode content = qwq.question().content();
            if (content != null && content.has("gaps")) {
                var gaps = content.get("gaps");
                int gapIdx = 0;
                for (JsonNode gap : gaps) {
                    if (gapIdx >= 10) break;
                    String answer = gap.has("answer") ? gap.get("answer").asText() : "";
                    row.createCell(colIdx++).setCellValue(answer);
                    gapIdx++;
                }
                // Fill empty cells for unused gaps
                for (int i = gapIdx; i < 10; i++) {
                    row.createCell(colIdx++).setCellValue("");
                }
            } else {
                // Fill all gap columns as empty
                for (int i = 0; i < 10; i++) {
                    row.createCell(colIdx++).setCellValue("");
                }
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createOrderingSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Items + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        
        // Item columns
        for (int i = 1; i <= 10; i++) {
            header.createCell(colIdx++).setCellValue("Item " + i);
        }
        
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Items
            JsonNode content = qwq.question().content();
            if (content != null && content.has("items")) {
                var items = content.get("items");
                int itemIdx = 0;
                for (JsonNode item : items) {
                    if (itemIdx >= 10) break;
                    String text = item.has("text") ? item.get("text").asText() : "";
                    row.createCell(colIdx++).setCellValue(text);
                    itemIdx++;
                }
                // Fill empty cells for unused items
                for (int i = itemIdx; i < 10; i++) {
                    row.createCell(colIdx++).setCellValue("");
                }
            } else {
                // Fill all item columns as empty
                for (int i = 0; i < 10; i++) {
                    row.createCell(colIdx++).setCellValue("");
                }
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createMatchingSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Matching pairs + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        
        // Left and right columns for matching
        for (int i = 1; i <= 8; i++) {
            header.createCell(colIdx++).setCellValue("Left " + i);
            header.createCell(colIdx++).setCellValue("Right " + i);
        }
        
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Matching pairs (complex type - leave empty for now, could be enhanced later)
            for (int i = 0; i < 16; i++) {
                row.createCell(colIdx++).setCellValue("");
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createComplianceSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Statements + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        
        // Statement columns
        for (int i = 1; i <= 10; i++) {
            header.createCell(colIdx++).setCellValue("Statement " + i);
            header.createCell(colIdx++).setCellValue("Statement " + i + " Compliant");
        }
        
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Statements
            JsonNode content = qwq.question().content();
            if (content != null && content.has("statements")) {
                var statements = content.get("statements");
                int stmtIdx = 0;
                for (JsonNode stmt : statements) {
                    if (stmtIdx >= 10) break;
                    String text = stmt.has("text") ? stmt.get("text").asText() : "";
                    boolean compliant = stmt.has("compliant") ? stmt.get("compliant").asBoolean() : false;
                    row.createCell(colIdx++).setCellValue(text);
                    row.createCell(colIdx++).setCellValue(compliant ? "Compliant" : "Non-compliant");
                    stmtIdx++;
                }
                // Fill empty cells for unused statements
                for (int i = stmtIdx; i < 10; i++) {
                    row.createCell(colIdx++).setCellValue("");
                    row.createCell(colIdx++).setCellValue("");
                }
            } else {
                // Fill all statement columns as empty
                for (int i = 0; i < 10; i++) {
                    row.createCell(colIdx++).setCellValue("");
                    row.createCell(colIdx++).setCellValue("");
                }
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createHotspotSheet(Sheet sheet, List<QuestionWithQuizId> questions) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        
        // Headers: Common before + Hotspot info + Common after
        int colIdx = createCommonHeadersBeforeContent(header);
        header.createCell(colIdx++).setCellValue("Image URL");
        header.createCell(colIdx++).setCellValue("Hotspot Count");
        int lastCol = createCommonHeadersAfterContent(header, colIdx);
        
        // Data rows
        for (QuestionWithQuizId qwq : questions) {
            Row row = sheet.createRow(rowIdx++);
            
            // Common fields before content
            colIdx = fillCommonDataBeforeContent(row, qwq);
            
            // Hotspot-specific fields
            JsonNode content = qwq.question().content();
            if (content != null) {
                if (content.has("imageUrl")) {
                    row.createCell(colIdx++).setCellValue(content.get("imageUrl").asText());
                } else {
                    row.createCell(colIdx++).setCellValue("");
                }
                if (content.has("hotspots")) {
                    row.createCell(colIdx++).setCellValue(content.get("hotspots").size());
                } else {
                    row.createCell(colIdx++).setCellValue(0);
                }
            } else {
                row.createCell(colIdx++).setCellValue("");
                row.createCell(colIdx++).setCellValue(0);
            }
            
            // Common fields after content
            fillCommonDataAfterContent(row, qwq, colIdx);
        }
        
        // Autosize columns
        for (int i = 0; i < lastCol; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}


