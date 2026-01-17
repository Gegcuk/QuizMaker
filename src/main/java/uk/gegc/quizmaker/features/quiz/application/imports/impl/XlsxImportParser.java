package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportParser;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.shared.exception.UnsupportedQuestionTypeException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
public class XlsxImportParser implements ImportParser {

    private static final String CDN_HOST = "cdn.quizzence.com";
    private static final String QUIZZES_SHEET = "Quizzes";
    private static final String METADATA_SHEET = "Metadata";

    private final ObjectMapper objectMapper;

    @Override
    public List<QuizImportDto> parse(InputStream input, QuizImportOptions options) {
        if (input == null) {
            throw new ValidationException("Import input stream is required");
        }
        if (options == null) {
            throw new IllegalArgumentException("QuizImportOptions is required");
        }

        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet quizzesSheet = workbook.getSheet(QUIZZES_SHEET);
            if (quizzesSheet == null) {
                throw new ValidationException("Missing required '" + QUIZZES_SHEET + "' sheet");
            }

            SchemaVersionInfo schemaInfo = resolveSchemaVersion(workbook, quizzesSheet);
            List<QuizImportDto> quizzes = parseQuizzesSheet(quizzesSheet, schemaInfo.headerRowIndex(), options.maxItems());

            Map<UUID, Integer> quizIndexById = new HashMap<>();
            for (int i = 0; i < quizzes.size(); i++) {
                UUID quizId = quizzes.get(i).id();
                if (quizId != null) {
                    quizIndexById.put(quizId, i);
                }
            }

            Map<UUID, List<QuestionImportDto>> questionsByQuizId = new HashMap<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }
                String sheetName = sheet.getSheetName();
                if (QUIZZES_SHEET.equalsIgnoreCase(sheetName) || METADATA_SHEET.equalsIgnoreCase(sheetName)) {
                    continue;
                }
                QuestionType type = parseQuestionType(sheetName);
                if (type == null) {
                    continue;
                }
                if (type == QuestionType.HOTSPOT) {
                    throw new UnsupportedQuestionTypeException("HOTSPOT questions are not supported for import");
                }
                if (type == QuestionType.MATCHING) {
                    throw new UnsupportedQuestionTypeException("MATCHING questions require JSON_EDITABLE import");
                }
                parseQuestionSheet(sheet, type, questionsByQuizId);
            }

            for (UUID quizId : questionsByQuizId.keySet()) {
                if (!quizIndexById.containsKey(quizId)) {
                    throw new ValidationException("Quiz ID " + quizId + " referenced in questions but not found in quizzes sheet");
                }
            }

            List<QuizImportDto> results = new ArrayList<>(quizzes.size());
            for (QuizImportDto quiz : quizzes) {
                List<QuestionImportDto> questions = quiz.id() != null
                        ? questionsByQuizId.getOrDefault(quiz.id(), List.of())
                        : List.of();
                results.add(new QuizImportDto(
                        schemaInfo.schemaVersion() != null ? schemaInfo.schemaVersion() : quiz.schemaVersion(),
                        quiz.id(),
                        quiz.title(),
                        quiz.description(),
                        quiz.visibility(),
                        quiz.difficulty(),
                        quiz.estimatedTime(),
                        quiz.tags(),
                        quiz.category(),
                        quiz.creatorId(),
                        questions,
                        quiz.createdAt(),
                        quiz.updatedAt()
                ));
            }
            return results;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (ValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ValidationException("Failed to parse XLSX import file");
        }
    }

    private SchemaVersionInfo resolveSchemaVersion(Workbook workbook, Sheet quizzesSheet) {
        Integer schemaVersion = readSchemaVersionFromMetadata(workbook);
        int headerRowIndex = 0;
        Row firstRow = quizzesSheet.getRow(0);
        if (firstRow != null) {
            String firstCell = readCell(firstRow.getCell(0));
            if (firstCell != null && "schemaVersion".equalsIgnoreCase(firstCell.trim())) {
                schemaVersion = parseInteger(readCell(firstRow.getCell(1)));
                headerRowIndex = 1;
            }
        }
        return new SchemaVersionInfo(schemaVersion, headerRowIndex);
    }

    private Integer readSchemaVersionFromMetadata(Workbook workbook) {
        Sheet metadata = findSheet(workbook, METADATA_SHEET);
        if (metadata == null) {
            return null;
        }
        for (int i = 0; i <= metadata.getLastRowNum(); i++) {
            Row row = metadata.getRow(i);
            if (row == null) {
                continue;
            }
            String key = readCell(row.getCell(0));
            if (key != null && "schemaVersion".equalsIgnoreCase(key.trim())) {
                return parseInteger(readCell(row.getCell(1)));
            }
        }
        return null;
    }

    private Sheet findSheet(Workbook workbook, String name) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (sheet != null && name.equalsIgnoreCase(sheet.getSheetName())) {
                return sheet;
            }
        }
        return null;
    }

    private List<QuizImportDto> parseQuizzesSheet(Sheet sheet, int headerRowIndex, int maxItems) {
        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) {
            throw new ValidationException("Quizzes sheet is missing header row");
        }
        Map<String, Integer> headers = mapHeaders(headerRow);

        List<QuizImportDto> quizzes = new ArrayList<>();
        for (int rowIdx = headerRowIndex + 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (isRowEmpty(row)) {
                continue;
            }

            UUID id = parseUuid(readCell(row.getCell(getHeader(headers, "Quiz ID"))), "Quiz ID");
            String title = readCell(row.getCell(getHeader(headers, "Title")));
            String description = readCell(row.getCell(getHeader(headers, "Description")));
            Visibility visibility = parseEnum(readCell(row.getCell(getHeader(headers, "Visibility"))), Visibility.class);
            Difficulty difficulty = parseEnum(readCell(row.getCell(getHeader(headers, "Difficulty"))), Difficulty.class);
            Integer estimatedTime = parseInteger(readCell(row.getCell(getHeader(headers, "Estimated Time (min)"))));
            if (estimatedTime != null && estimatedTime <= 0) {
                estimatedTime = null;
            }
            List<String> tags = parseTags(readCell(row.getCell(getHeader(headers, "Tags"))));
            String category = readCell(row.getCell(getHeader(headers, "Category")));
            UUID creatorId = parseUuid(readCell(row.getCell(getHeader(headers, "Creator ID"))), "Creator ID");
            Instant createdAt = parseInstant(readCell(row.getCell(getHeader(headers, "Created At"))));
            Instant updatedAt = parseInstant(readCell(row.getCell(getHeader(headers, "Updated At"))));

            quizzes.add(new QuizImportDto(
                    null,
                    id,
                    title,
                    description,
                    visibility,
                    difficulty,
                    estimatedTime,
                    tags,
                    category,
                    creatorId,
                    List.of(),
                    createdAt,
                    updatedAt
            ));

            if (quizzes.size() > maxItems) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Import file exceeds max items limit of " + maxItems
                );
            }
        }
        return quizzes;
    }

    private void parseQuestionSheet(Sheet sheet, QuestionType type, Map<UUID, List<QuestionImportDto>> questionsByQuizId) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            return;
        }
        Map<String, Integer> headers = mapHeaders(headerRow);
        int quizIdCol = getHeader(headers, "Quiz ID");
        int questionTextCol = getHeader(headers, "Question Text");

        for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (isRowEmpty(row)) {
                continue;
            }

            UUID quizId = parseUuid(readCell(row.getCell(quizIdCol)), "Quiz ID");
            if (quizId == null) {
                throw new ValidationException("Question row is missing Quiz ID in sheet " + sheet.getSheetName());
            }

            int questionIdCol = headerIndex(headers, "Question ID");
            UUID questionId = questionIdCol >= 0 ? parseUuid(readCell(row.getCell(questionIdCol)), "Question ID") : null;
            int difficultyCol = headerIndex(headers, "Difficulty");
            Difficulty difficulty = difficultyCol >= 0
                    ? parseEnum(readCell(row.getCell(difficultyCol)), Difficulty.class)
                    : null;
            String questionText = readCell(row.getCell(questionTextCol));
            int hintCol = headerIndex(headers, "Hint");
            String hint = hintCol >= 0 ? readCell(row.getCell(hintCol)) : null;
            int explanationCol = headerIndex(headers, "Explanation");
            String explanation = explanationCol >= 0 ? readCell(row.getCell(explanationCol)) : null;
            int attachmentCol = headerIndex(headers, "Attachment URL");
            String attachmentUrl = attachmentCol >= 0 ? readCell(row.getCell(attachmentCol)) : null;
            validateAttachmentUrl(attachmentUrl);

            ObjectNode content = buildContentForType(type, headers, row, questionText);

            QuestionImportDto question = new QuestionImportDto(
                    questionId,
                    type,
                    difficulty,
                    questionText,
                    content,
                    hint,
                    explanation,
                    attachmentUrl
            );

            questionsByQuizId
                    .computeIfAbsent(quizId, key -> new ArrayList<>())
                    .add(question);
        }
    }

    private ObjectNode buildContentForType(QuestionType type, Map<String, Integer> headers, Row row, String questionText) {
        return switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> buildMcqContent(headers, row);
            case TRUE_FALSE -> buildTrueFalseContent(headers, row);
            case OPEN -> buildOpenContent(headers, row);
            case FILL_GAP -> buildFillGapContent(headers, row, questionText);
            case ORDERING -> buildOrderingContent(headers, row);
            case COMPLIANCE -> buildComplianceContent(headers, row);
            default -> throw new UnsupportedQuestionTypeException("Unsupported XLSX question type: " + type);
        };
    }

    private ObjectNode buildMcqContent(Map<String, Integer> headers, Row row) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        int optionIndex = 1;
        for (int i = 1; i <= 6; i++) {
            int optionCol = getHeader(headers, "Option " + i);
            String text = readCell(row.getCell(optionCol));
            if (text == null || text.isBlank()) {
                continue;
            }
            int correctCol = getHeader(headers, "Option " + i + " Correct");
            String correctRaw = readCell(row.getCell(correctCol));
            boolean correct = parseBooleanLoose(correctRaw, "Option " + i + " Correct");

            ObjectNode option = objectMapper.createObjectNode();
            option.put("id", "opt_" + optionIndex++);
            option.put("text", text);
            option.put("correct", correct);
            options.add(option);
        }
        content.set("options", options);
        return content;
    }

    private ObjectNode buildTrueFalseContent(Map<String, Integer> headers, Row row) {
        ObjectNode content = objectMapper.createObjectNode();
        int answerCol = getHeader(headers, "Correct Answer");
        String answerRaw = readCell(row.getCell(answerCol));
        boolean answer = parseBooleanRequired(answerRaw, "Correct Answer");
        content.put("answer", answer);
        return content;
    }

    private ObjectNode buildOpenContent(Map<String, Integer> headers, Row row) {
        ObjectNode content = objectMapper.createObjectNode();
        int answerCol = getHeader(headers, "Sample Answer");
        String answer = readCell(row.getCell(answerCol));
        if (answer != null) {
            content.put("answer", answer);
        }
        return content;
    }

    private ObjectNode buildFillGapContent(Map<String, Integer> headers, Row row, String questionText) {
        ObjectNode content = objectMapper.createObjectNode();
        if (questionText != null) {
            content.put("text", questionText);
        }
        ArrayNode gaps = objectMapper.createArrayNode();
        for (int i = 1; i <= 10; i++) {
            int gapCol = getHeader(headers, "Gap " + i + " Answer");
            String answer = readCell(row.getCell(gapCol));
            if (answer == null || answer.isBlank()) {
                continue;
            }
            ObjectNode gap = objectMapper.createObjectNode();
            gap.put("id", i);
            gap.put("answer", answer);
            gaps.add(gap);
        }
        content.set("gaps", gaps);
        return content;
    }

    private ObjectNode buildOrderingContent(Map<String, Integer> headers, Row row) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        ArrayNode correctOrder = objectMapper.createArrayNode();
        int itemCount = 0;
        for (int i = 1; i <= 10; i++) {
            int itemCol = getHeader(headers, "Item " + i);
            String text = readCell(row.getCell(itemCol));
            if (text == null || text.isBlank()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", i);
            item.put("text", text);
            items.add(item);
            correctOrder.add(i);
            itemCount++;
        }
        if (itemCount > 0) {
            content.set("items", items);
            content.set("correctOrder", correctOrder);
        }
        return content;
    }

    private ObjectNode buildComplianceContent(Map<String, Integer> headers, Row row) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode statements = objectMapper.createArrayNode();
        for (int i = 1; i <= 10; i++) {
            int statementCol = getHeader(headers, "Statement " + i);
            String text = readCell(row.getCell(statementCol));
            if (text == null || text.isBlank()) {
                continue;
            }
            int compliantCol = getHeader(headers, "Statement " + i + " Compliant");
            String compliantRaw = readCell(row.getCell(compliantCol));
            boolean compliant = parseBooleanRequired(compliantRaw, "Statement " + i + " Compliant");

            ObjectNode statement = objectMapper.createObjectNode();
            statement.put("id", i);
            statement.put("text", text);
            statement.put("compliant", compliant);
            statements.add(statement);
        }
        content.set("statements", statements);
        return content;
    }

    private QuestionType parseQuestionType(String sheetName) {
        try {
            return QuestionType.valueOf(sheetName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Map<String, Integer> mapHeaders(Row headerRow) {
        Map<String, Integer> headers = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        short lastCell = headerRow.getLastCellNum();
        for (int i = 0; i < lastCell; i++) {
            Cell cell = headerRow.getCell(i);
            if (cell == null) {
                continue;
            }
            String value = formatter.formatCellValue(cell);
            if (value != null && !value.isBlank()) {
                headers.put(value.trim(), i);
            }
        }
        return headers;
    }

    private int getHeader(Map<String, Integer> headers, String name) {
        Integer idx = headers.get(name);
        if (idx == null) {
            throw new ValidationException("Missing required column '" + name + "'");
        }
        return idx;
    }

    private int headerIndex(Map<String, Integer> headers, String name) {
        return headers.getOrDefault(name, -1);
    }

    private String readCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        DataFormatter formatter = new DataFormatter();
        String value = formatter.formatCellValue(cell);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) {
            return true;
        }
        DataFormatter formatter = new DataFormatter();
        short firstCell = row.getFirstCellNum();
        short lastCell = row.getLastCellNum();
        if (firstCell < 0 || lastCell < 0) {
            return true;
        }
        for (int i = firstCell; i < lastCell; i++) {
            Cell cell = row.getCell(i);
            if (cell == null) {
                continue;
            }
            String value = formatter.formatCellValue(cell);
            if (value != null && !value.trim().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private <T extends Enum<T>> T parseEnum(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Invalid value '" + raw + "' for " + type.getSimpleName());
        }
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Double value = Double.valueOf(raw.trim());
            return value.intValue();
        } catch (NumberFormatException ex) {
            throw new ValidationException("Expected numeric value but got '" + raw + "'");
        }
    }

    private UUID parseUuid(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(fieldName + " must be a valid UUID");
        }
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> tags = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    private boolean parseBooleanLoose(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "yes", "true", "1", "y", "compliant", "correct" -> true;
            case "no", "false", "0", "n", "non-compliant", "incorrect" -> false;
            default -> throw new ValidationException("Invalid boolean value for " + fieldName + ": " + raw);
        };
    }

    private boolean parseBooleanRequired(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("Missing boolean value for " + fieldName);
        }
        return parseBooleanLoose(raw, fieldName);
    }

    private void validateAttachmentUrl(String attachmentUrl) {
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(attachmentUrl);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("attachmentUrl must be a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("attachmentUrl must use https");
        }
        if (!CDN_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new ValidationException("attachmentUrl must use host " + CDN_HOST);
        }
    }

    private record SchemaVersionInfo(Integer schemaVersion, int headerRowIndex) {
    }
}
