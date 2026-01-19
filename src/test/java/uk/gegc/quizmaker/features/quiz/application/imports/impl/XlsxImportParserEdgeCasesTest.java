package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("XlsxImportParser Edge Cases")
class XlsxImportParserEdgeCasesTest extends BaseUnitTest {

    private static final String[] QUIZ_HEADERS = {
            "Quiz ID",
            "Title",
            "Description",
            "Visibility",
            "Difficulty",
            "Estimated Time (min)",
            "Tags",
            "Category",
            "Creator ID",
            "Created At",
            "Updated At"
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XlsxImportParser parser = new XlsxImportParser(objectMapper);
    private final QuizImportOptions options = QuizImportOptions.defaults(10);

    @Test
    @DisplayName("parse rejects null input stream")
    void parse_nullInputStream_throwsException() {
        assertThatThrownBy(() -> parser.parse(null, options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Import input stream is required");
    }

    @Test
    @DisplayName("parse rejects null options")
    void parse_nullOptions_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        createQuizzesSheet(workbook);

        assertThatThrownBy(() -> parser.parse(input(workbook), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QuizImportOptions is required");
    }

    @Test
    @DisplayName("parse rejects invalid workbook format")
    void parse_invalidWorkbookFormat_throwsException() {
        ByteArrayInputStream invalidInput = new ByteArrayInputStream("not a workbook".getBytes());

        assertThatThrownBy(() -> parser.parse(invalidInput, options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Failed to parse XLSX import file");
    }

    @Test
    @DisplayName("parse rejects invalid UUID format")
    void parse_invalidUuidFormat_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Quiz ID")).setCellValue("not-a-uuid");
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz ID must be a valid UUID");
    }

    @Test
    @DisplayName("parse handles empty UUID string")
    void parse_emptyUuidString_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Quiz ID")).setCellValue("");
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isNull();
    }

    @Test
    @DisplayName("parse handles whitespace-only UUID")
    void parse_whitespaceOnlyUuid_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Quiz ID")).setCellValue("   ");
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isNull();
    }

    @Test
    @DisplayName("parse trims whitespace from UUID")
    void parse_uuidWithWhitespace_trimmed() {
        UUID quizId = UUID.randomUUID();
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Quiz ID")).setCellValue("  " + quizId.toString() + "  ");
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(quizId);
    }

    @Test
    @DisplayName("parse rejects invalid enum value")
    void parse_invalidEnumValue_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Visibility")).setCellValue("INVALID");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid value 'INVALID' for Visibility");
    }

    @Test
    @DisplayName("parse handles enum case-insensitive")
    void parse_enumCaseInsensitive_passes() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Visibility")).setCellValue("private");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).visibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    @DisplayName("parse handles empty enum string")
    void parse_emptyEnumString_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Visibility")).setCellValue("");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).visibility()).isNull();
    }

    @Test
    @DisplayName("parse handles whitespace-only enum")
    void parse_whitespaceOnlyEnum_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Visibility")).setCellValue("   ");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).visibility()).isNull();
    }

    @Test
    @DisplayName("parse rejects invalid integer format")
    void parse_invalidIntegerFormat_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue("not a number");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Expected numeric value");
    }

    @Test
    @DisplayName("parse converts decimal number to integer")
    void parse_decimalNumber_convertsToInt() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(15.7);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).estimatedTime()).isEqualTo(15);
    }

    @Test
    @DisplayName("parse handles negative integer")
    void parse_negativeInteger_passes() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(-5);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).estimatedTime()).isNull();
    }

    @Test
    @DisplayName("parse converts zero estimated time to null")
    void parse_zeroEstimatedTime_becomesNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(0);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).estimatedTime()).isNull();
    }

    @Test
    @DisplayName("parse converts negative estimated time to null")
    void parse_negativeEstimatedTime_becomesNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(-1);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).estimatedTime()).isNull();
    }

    @Test
    @DisplayName("parse handles invalid timestamp format")
    void parse_invalidTimestampFormat_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Created At")).setCellValue("invalid timestamp");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).createdAt()).isNull();
    }

    @Test
    @DisplayName("parse handles empty timestamp")
    void parse_emptyTimestamp_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Created At")).setCellValue("");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).createdAt()).isNull();
    }

    @Test
    @DisplayName("parse handles malformed timestamp")
    void parse_malformedTimestamp_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Created At")).setCellValue("2024-13-45T99:99:99Z");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).createdAt()).isNull();
    }

    @Test
    @DisplayName("parse trims whitespace from timestamp")
    void parse_timestampWithWhitespace_trimmed() {
        Instant timestamp = Instant.parse("2024-01-01T10:00:00Z");
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Created At")).setCellValue("  " + timestamp.toString() + "  ");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).createdAt()).isEqualTo(timestamp);
    }

    @Test
    @DisplayName("parse handles empty tags string")
    void parse_emptyTagsString_returnsEmptyList() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Tags")).setCellValue("");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tags()).isEmpty();
    }

    @Test
    @DisplayName("parse trims extra whitespace from tags")
    void parse_tagsWithExtraWhitespace_trimmed() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Tags")).setCellValue("  tag1  ,  tag2  ,  tag3  ");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tags()).containsExactly("tag1", "tag2", "tag3");
    }

    @Test
    @DisplayName("parse filters empty tag entries")
    void parse_tagsWithEmptyEntries_filtered() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Tags")).setCellValue("tag1,,tag2,  ,tag3");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tags()).containsExactly("tag1", "tag2", "tag3");
    }

    @Test
    @DisplayName("parse handles tags with commas in values")
    void parse_tagsWithCommas_handled() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        row.createCell(headerIndex("Tags")).setCellValue("tag1,tag,with,commas,tag2");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        // Tags are split by comma, so "tag,with,commas" becomes separate tags
        assertThat(result.get(0).tags()).containsExactly("tag1", "tag", "with", "commas", "tag2");
    }

    @Test
    @DisplayName("parse filters null tag entries")
    void parse_tagsWithNullEntries_filtered() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        // Empty cell is treated as null/empty string
        row.createCell(headerIndex("Tags")).setCellValue("tag1,,tag2");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tags()).containsExactly("tag1", "tag2");
    }

    @Test
    @DisplayName("parse handles boolean yes/no")
    void parse_booleanYesNo_passes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("yes");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("answer").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("parse handles boolean true/false")
    void parse_booleanTrueFalse_passes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("false");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("answer").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("parse handles boolean 1/0")
    void parse_booleanOneZero_passes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("1");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("answer").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("parse handles boolean y/n")
    void parse_booleanYN_passes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("n");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("answer").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("parse handles boolean compliant/non-compliant")
    void parse_booleanCompliantNonCompliant_passes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "COMPLIANCE", complianceHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(complianceHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(complianceHeaders(), "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1")).setCellValue("S1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1 Compliant")).setCellValue("compliant");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("statements").get(0).get("compliant").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("parse handles boolean correct/incorrect")
    void parse_booleanCorrectIncorrect_passes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = mcqHeaders();
        Sheet sheet = createQuestionSheet(workbook, "MCQ_SINGLE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Option 1")).setCellValue("A");
        row.createCell(headerIndex(headers, "Option 1 Correct")).setCellValue("correct");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("options").get(0).get("correct").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("parse rejects invalid boolean value")
    void parse_invalidBooleanValue_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("maybe");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid boolean value");
    }

    @Test
    @DisplayName("parse rejects empty required boolean")
    void parse_emptyRequiredBoolean_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "COMPLIANCE", complianceHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(complianceHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(complianceHeaders(), "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1")).setCellValue("S1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1 Compliant")).setCellValue("");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Missing boolean value");
    }

    @Test
    @DisplayName("parse handles whitespace-only boolean")
    void parse_whitespaceOnlyBoolean_handled() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = mcqHeaders();
        Sheet sheet = createQuestionSheet(workbook, "MCQ_SINGLE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Option 1")).setCellValue("A");
        row.createCell(headerIndex(headers, "Option 1 Correct")).setCellValue("   ");

        // Whitespace-only is trimmed to empty, which is blank, so parseBooleanLoose returns false
        // This is valid for loose boolean parsing (not required)
        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("options").get(0).get("correct").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("parse handles null cell")
    void parse_nullCell_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        // Description cell is not created, so it's null

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).description()).isNull();
    }

    @Test
    @DisplayName("parse handles empty cell")
    void parse_emptyCell_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Description")).setCellValue("");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).description()).isNull();
    }

    @Test
    @DisplayName("parse handles whitespace-only cell")
    void parse_whitespaceOnlyCell_returnsNull() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Description")).setCellValue("   ");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).description()).isNull();
    }

    @Test
    @DisplayName("parse converts numeric cell to string")
    void parse_numericCell_convertedToString() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue(12345.0); // Numeric cell
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("12345");
    }

    @Test
    @DisplayName("parse handles formula cell - DataFormatter returns formula string")
    void parse_formulaCell_evaluated() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Quiz ID")).setCellValue(UUID.randomUUID().toString());
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Description")).setCellValue("");
        row.createCell(headerIndex("Visibility")).setCellValue("");
        row.createCell(headerIndex("Difficulty")).setCellValue("");
        Cell formulaCell = row.createCell(headerIndex("Estimated Time (min)"));
        formulaCell.setCellFormula("5+5");
        row.createCell(headerIndex("Tags")).setCellValue("");
        row.createCell(headerIndex("Category")).setCellValue("");
        row.createCell(headerIndex("Creator ID")).setCellValue("");
        row.createCell(headerIndex("Created At")).setCellValue("");
        row.createCell(headerIndex("Updated At")).setCellValue("");

        // DataFormatter may return the formula string "5+5" instead of evaluated value
        // This causes parseInteger to throw ValidationException
        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Expected numeric value");
    }

    @Test
    @DisplayName("parse formats date cell correctly")
    void parse_dateCell_formatted() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        Cell dateCell = row.createCell(headerIndex("Created At"));
        dateCell.setCellValue(LocalDate.of(2024, 1, 1));

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        // DataFormatter formats date as string, parseInstant tries to parse it
        // If it can't parse, it returns null
        assertThat(result.get(0).createdAt()).isNull();
    }

    @Test
    @DisplayName("parse skips empty row")
    void parse_emptyRow_skipped() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row1 = quizzes.createRow(1);
        row1.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row1.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        Row emptyRow = quizzes.createRow(2); // Empty row
        Row row3 = quizzes.createRow(3);
        row3.createCell(headerIndex("Title")).setCellValue("Quiz 2");
        row3.createCell(headerIndex("Estimated Time (min)")).setCellValue(5);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("parse skips row with only whitespace")
    void parse_rowWithOnlyWhitespace_skipped() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row1 = quizzes.createRow(1);
        row1.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row1.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        Row whitespaceRow = quizzes.createRow(2);
        whitespaceRow.createCell(headerIndex("Title")).setCellValue("   ");
        Row row3 = quizzes.createRow(3);
        row3.createCell(headerIndex("Title")).setCellValue("Quiz 2");
        row3.createCell(headerIndex("Estimated Time (min)")).setCellValue(5);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("parse handles missing row gracefully")
    void parse_missingRow_handled() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row1 = quizzes.createRow(1);
        row1.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row1.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        // Row 2 is missing
        Row row3 = quizzes.createRow(3);
        row3.createCell(headerIndex("Title")).setCellValue("Quiz 2");
        row3.createCell(headerIndex("Estimated Time (min)")).setCellValue(5);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("parse rejects question with missing Quiz ID")
    void parse_questionMissingQuizId_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        // Quiz ID cell is missing
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question row is missing Quiz ID");
    }

    @Test
    @DisplayName("parse rejects question with non-existent Quiz ID")
    void parse_questionWithNonExistentQuizId_throwsException() {
        UUID quizId = UUID.randomUUID();
        Workbook workbook = workbookWithQuiz(quizId);
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(UUID.randomUUID().toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("referenced in questions but not found in quizzes sheet");
    }

    @Test
    @DisplayName("parse groups multiple questions for same quiz")
    void parse_multipleQuestionsForSameQuiz_grouped() {
        UUID quizId = UUID.randomUUID();
        Workbook workbook = workbookWithQuiz(quizId);
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row1 = sheet.createRow(1);
        row1.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId.toString());
        row1.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row1.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer1");
        Row row2 = sheet.createRow(2);
        row2.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId.toString());
        row2.createCell(headerIndex(headers, "Question Text")).setCellValue("Q2");
        row2.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer2");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).questions()).hasSize(2);
    }

    @Test
    @DisplayName("parse rejects question with missing required column")
    void parse_questionWithMissingRequiredColumn_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text"}; // Missing "Sample Answer" for OPEN
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Missing required column");
    }

    @Test
    @DisplayName("parse rejects invalid attachment URL format")
    void parse_invalidAttachmentUrlFormat_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Attachment URL"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Attachment URL")).setCellValue("not a valid url");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("attachmentUrl must be a valid URL");
    }

    @Test
    @DisplayName("parse rejects HTTP attachment URL")
    void parse_attachmentUrlNotHttps_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Attachment URL"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Attachment URL")).setCellValue("http://cdn.quizzence.com/att.png");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("attachmentUrl must use https");
    }

    @Test
    @DisplayName("parse rejects attachment URL with wrong host")
    void parse_attachmentUrlWrongHost_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Attachment URL"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Attachment URL")).setCellValue("https://example.com/att.png");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("attachmentUrl must use host cdn.quizzence.com");
    }

    @Test
    @DisplayName("parse allows null attachment URL")
    void parse_nullAttachmentUrl_allowed() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer");
        // Attachment URL cell not created

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.attachmentUrl()).isNull();
    }

    @Test
    @DisplayName("parse allows empty attachment URL")
    void parse_emptyAttachmentUrl_allowed() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer", "Attachment URL"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer");
        row.createCell(headerIndex(headers, "Attachment URL")).setCellValue("");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.attachmentUrl()).isNull();
    }

    @Test
    @DisplayName("parse handles MCQ with no options")
    void parse_mcqWithNoOptions_handlesGracefully() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = mcqHeaders();
        Sheet sheet = createQuestionSheet(workbook, "MCQ_SINGLE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        // No options provided

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("options")).isNotNull();
        assertThat(question.content().get("options").size()).isZero();
    }

    @Test
    @DisplayName("parse handles MCQ with partial options")
    void parse_mcqWithPartialOptions_handlesGracefully() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = mcqHeaders();
        Sheet sheet = createQuestionSheet(workbook, "MCQ_SINGLE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Option 1")).setCellValue("A");
        row.createCell(headerIndex(headers, "Option 1 Correct")).setCellValue("true");
        row.createCell(headerIndex(headers, "Option 3")).setCellValue("C");
        row.createCell(headerIndex(headers, "Option 3 Correct")).setCellValue("false");
        // Option 2 is missing

        QuestionImportDto question = parseSingleQuestion(workbook);

        JsonNode options = question.content().get("options");
        assertThat(options.size()).isEqualTo(2);
        assertThat(options.get(0).get("text").asText()).isEqualTo("A");
        assertThat(options.get(1).get("text").asText()).isEqualTo("C");
    }

    @Test
    @DisplayName("parse handles FILL_GAP with gaps not in sequence")
    void parse_fillGapWithGapsNotInSequence_handlesCorrectly() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "FILL_GAP", fillGapHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(fillGapHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(fillGapHeaders(), "Question Text")).setCellValue("Text with gaps");
        row.createCell(headerIndex(fillGapHeaders(), "Gap 1 Answer")).setCellValue("alpha");
        row.createCell(headerIndex(fillGapHeaders(), "Gap 3 Answer")).setCellValue("gamma");
        row.createCell(headerIndex(fillGapHeaders(), "Gap 5 Answer")).setCellValue("epsilon");
        // Gaps 2 and 4 are missing

        QuestionImportDto question = parseSingleQuestion(workbook);
        JsonNode gaps = question.content().get("gaps");

        assertThat(gaps.size()).isEqualTo(3);
        assertThat(gaps.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(gaps.get(1).get("id").asInt()).isEqualTo(3);
        assertThat(gaps.get(2).get("id").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("parse handles ORDERING with no items")
    void parse_orderingWithNoItems_handlesGracefully() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "ORDERING", orderingHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(orderingHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(orderingHeaders(), "Question Text")).setCellValue("Q1");
        // No items provided

        QuestionImportDto question = parseSingleQuestion(workbook);

        JsonNode content = question.content();
        assertThat(content.has("items")).isFalse();
        assertThat(content.has("correctOrder")).isFalse();
    }

    @Test
    @DisplayName("parse handles COMPLIANCE with no statements")
    void parse_complianceWithNoStatements_handlesGracefully() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "COMPLIANCE", complianceHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(complianceHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(complianceHeaders(), "Question Text")).setCellValue("Q1");
        // No statements provided

        QuestionImportDto question = parseSingleQuestion(workbook);

        JsonNode statements = question.content().get("statements");
        assertThat(statements).isNotNull();
        assertThat(statements.size()).isZero();
    }

    @Test
    @DisplayName("parse rejects COMPLIANCE with missing compliant field")
    void parse_complianceWithMissingCompliantField_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "COMPLIANCE", complianceHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(complianceHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(complianceHeaders(), "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1")).setCellValue("S1");
        // Statement 1 Compliant cell is not created (missing/null)

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Missing boolean value for Statement 1 Compliant");
    }

    @Test
    @DisplayName("parse handles schema version in both A1 and Metadata - A1 takes precedence")
    void parse_schemaVersionInBothA1AndMetadata_a1TakesPrecedence() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = workbook.createSheet("Quizzes");
        Row metaRow = quizzes.createRow(0);
        metaRow.createCell(0).setCellValue("schemaVersion");
        metaRow.createCell(1).setCellValue("2");
        Row header = quizzes.createRow(1);
        for (int i = 0; i < QUIZ_HEADERS.length; i++) {
            header.createCell(i).setCellValue(QUIZ_HEADERS[i]);
        }
        Row row = quizzes.createRow(2);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        Sheet metadata = workbook.createSheet("Metadata");
        Row metadataRow = metadata.createRow(0);
        metadataRow.createCell(0).setCellValue("schemaVersion");
        metadataRow.createCell(1).setCellValue("3");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("parse handles invalid schema version format")
    void parse_invalidSchemaVersionFormat_handled() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = workbook.createSheet("Quizzes");
        Row metaRow = quizzes.createRow(0);
        metaRow.createCell(0).setCellValue("schemaVersion");
        metaRow.createCell(1).setCellValue("not a number");
        Row header = quizzes.createRow(1);
        for (int i = 0; i < QUIZ_HEADERS.length; i++) {
            header.createCell(i).setCellValue(QUIZ_HEADERS[i]);
        }
        Row row = quizzes.createRow(2);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        // parseInteger throws exception for invalid format, so schema version becomes null
        // and defaults to 1
        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Expected numeric value");
    }

    @Test
    @DisplayName("parse handles schema version as non-integer")
    void parse_schemaVersionAsNonInteger_handled() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = workbook.createSheet("Quizzes");
        Row metaRow = quizzes.createRow(0);
        metaRow.createCell(0).setCellValue("schemaVersion");
        metaRow.createCell(1).setCellValue(2.5);
        Row header = quizzes.createRow(1);
        for (int i = 0; i < QUIZ_HEADERS.length; i++) {
            header.createCell(i).setCellValue(QUIZ_HEADERS[i]);
        }
        Row row = quizzes.createRow(2);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("parse rejects missing header row")
    void parse_missingHeaderRow_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = workbook.createSheet("Quizzes");
        // No rows at all - headerRowIndex is 0, but row 0 doesn't exist
        // This will cause headerRow to be null when accessed

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quizzes sheet is missing header row");
    }

    @Test
    @DisplayName("parse uses last occurrence for duplicate column names")
    void parse_duplicateColumnNames_usesLast() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = workbook.createSheet("Quizzes");
        Row header = quizzes.createRow(0);
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Title");
        header.createCell(col++).setCellValue("Title"); // Duplicate
        header.createCell(col++).setCellValue("Description");
        header.createCell(col++).setCellValue("Visibility");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Estimated Time (min)");
        header.createCell(col++).setCellValue("Tags");
        header.createCell(col++).setCellValue("Category");
        header.createCell(col++).setCellValue("Creator ID");
        header.createCell(col++).setCellValue("Created At");
        header.createCell(col++).setCellValue("Updated At");
        Row row = quizzes.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(UUID.randomUUID().toString());
        row.createCell(col++).setCellValue("First Title");
        row.createCell(col++).setCellValue("Second Title");
        row.createCell(col++).setCellValue("");
        row.createCell(col++).setCellValue("");
        row.createCell(col++).setCellValue("");
        row.createCell(col++).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Second Title");
    }

    @Test
    @DisplayName("parse handles header row with empty cells")
    void parse_headerRowWithEmptyCells_handled() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = workbook.createSheet("Quizzes");
        Row header = quizzes.createRow(0);
        int col = 0;
        header.createCell(col++).setCellValue("Quiz ID");
        header.createCell(col++).setCellValue("Title");
        header.createCell(col++).setCellValue("Description");
        header.createCell(col++).setCellValue("Visibility");
        header.createCell(col++).setCellValue("Difficulty");
        header.createCell(col++).setCellValue("Estimated Time (min)");
        header.createCell(col++).setCellValue("Tags");
        header.createCell(col++).setCellValue("Category");
        header.createCell(col++).setCellValue("Creator ID");
        header.createCell(col++).setCellValue("Created At");
        header.createCell(col++).setCellValue(""); // Empty cell (ignored by mapHeaders)
        header.createCell(col++).setCellValue("Updated At");
        Row row = quizzes.createRow(1);
        col = 0;
        row.createCell(col++).setCellValue(UUID.randomUUID().toString());
        row.createCell(col++).setCellValue("Quiz 1");
        row.createCell(col++).setCellValue("");
        row.createCell(col++).setCellValue("");
        row.createCell(col++).setCellValue("");
        row.createCell(col++).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Quiz 1");
    }

    @Test
    @DisplayName("parse allows quiz with null ID")
    void parse_quizWithNullId_allowed() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        // Quiz ID cell not created
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isNull();
    }

    @Test
    @DisplayName("parse allows question with null ID")
    void parse_questionWithNullId_allowed() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer");
        // Question ID not provided

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.id()).isNull();
    }

    @Test
    @DisplayName("parse handles quizzes with same ID")
    void parse_quizzesWithSameId_handled() {
        UUID quizId = UUID.randomUUID();
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row1 = quizzes.createRow(1);
        row1.createCell(headerIndex("Quiz ID")).setCellValue(quizId.toString());
        row1.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row1.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        Row row2 = quizzes.createRow(2);
        row2.createCell(headerIndex("Quiz ID")).setCellValue(quizId.toString());
        row2.createCell(headerIndex("Title")).setCellValue("Quiz 2");
        row2.createCell(headerIndex("Estimated Time (min)")).setCellValue(5);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(quizId);
        assertThat(result.get(1).id()).isEqualTo(quizId);
    }

    private Sheet createQuizzesSheet(Workbook workbook) {
        return createQuizzesSheet(workbook, QUIZ_HEADERS);
    }

    private Sheet createQuizzesSheet(Workbook workbook, String[] headers) {
        Sheet sheet = workbook.createSheet("Quizzes");
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        return sheet;
    }

    private Sheet createQuestionSheet(Workbook workbook, String name, String[] headers) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        return sheet;
    }

    private int headerIndex(String name) {
        return headerIndex(QUIZ_HEADERS, name);
    }

    private int headerIndex(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Missing header: " + name);
    }

    private Workbook workbookWithQuiz(UUID quizId) {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Quiz ID")).setCellValue(quizId.toString());
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        return workbook;
    }

    private UUID quizId(Workbook workbook) {
        Sheet quizzes = workbook.getSheet("Quizzes");
        Row row = quizzes.getRow(1);
        return UUID.fromString(row.getCell(headerIndex("Quiz ID")).getStringCellValue());
    }

    private QuestionImportDto parseSingleQuestion(Workbook workbook) {
        List<QuestionImportDto> questions = parseQuestions(workbook);
        assertThat(questions).hasSize(1);
        return questions.get(0);
    }

    private List<QuestionImportDto> parseQuestions(Workbook workbook) {
        List<QuizImportDto> result = parser.parse(input(workbook), options);
        assertThat(result).hasSize(1);
        return result.get(0).questions();
    }

    private String[] mcqHeaders() {
        String[] headers = new String[2 + 12];
        headers[0] = "Quiz ID";
        headers[1] = "Question Text";
        int idx = 2;
        for (int i = 1; i <= 6; i++) {
            headers[idx++] = "Option " + i;
        }
        for (int i = 1; i <= 6; i++) {
            headers[idx++] = "Option " + i + " Correct";
        }
        return headers;
    }

    private String[] fillGapHeaders() {
        String[] headers = new String[2 + 10];
        headers[0] = "Quiz ID";
        headers[1] = "Question Text";
        int idx = 2;
        for (int i = 1; i <= 10; i++) {
            headers[idx++] = "Gap " + i + " Answer";
        }
        return headers;
    }

    private String[] orderingHeaders() {
        String[] headers = new String[2 + 10];
        headers[0] = "Quiz ID";
        headers[1] = "Question Text";
        int idx = 2;
        for (int i = 1; i <= 10; i++) {
            headers[idx++] = "Item " + i;
        }
        return headers;
    }

    private String[] complianceHeaders() {
        String[] headers = new String[2 + 20];
        headers[0] = "Quiz ID";
        headers[1] = "Question Text";
        int idx = 2;
        for (int i = 1; i <= 10; i++) {
            headers[idx++] = "Statement " + i;
        }
        for (int i = 1; i <= 10; i++) {
            headers[idx++] = "Statement " + i + " Compliant";
        }
        return headers;
    }

    private ByteArrayInputStream input(Workbook workbook) {
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return new ByteArrayInputStream(output.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write workbook", ex);
        }
    }
}
