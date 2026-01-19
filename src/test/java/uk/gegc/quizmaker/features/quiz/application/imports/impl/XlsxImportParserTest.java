package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("XlsxImportParser")
class XlsxImportParserTest extends BaseUnitTest {

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
    @DisplayName("parse requires Quizzes sheet")
    void parse_missingQuizzesSheet_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        workbook.createSheet("Other");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Missing required 'Quizzes' sheet");
    }

    @Test
    @DisplayName("parse allows missing question type sheets")
    void parse_missingQuestionTypeSheet_allows() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Quiz 1");
    }

    @Test
    @DisplayName("parse ignores unknown sheets")
    void parse_unknownSheet_ignored() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        workbook.createSheet("Notes").createRow(0).createCell(0).setCellValue("Ignored");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Quiz 1");
    }

    @Test
    @DisplayName("parse returns empty list for empty Quizzes sheet")
    void parse_emptyQuizzesSheet_returnsEmptyList() {
        Workbook workbook = new XSSFWorkbook();
        createQuizzesSheet(workbook);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("parse maps all quiz columns")
    void parse_mapsAllQuizColumns() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        UUID quizId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2024-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2024-01-02T12:00:00Z");

        row.createCell(headerIndex("Quiz ID")).setCellValue(quizId.toString());
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Description")).setCellValue("Desc");
        row.createCell(headerIndex("Visibility")).setCellValue("PUBLIC");
        row.createCell(headerIndex("Difficulty")).setCellValue("EASY");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(15);
        row.createCell(headerIndex("Tags")).setCellValue("tag1, tag2");
        row.createCell(headerIndex("Category")).setCellValue("Science");
        row.createCell(headerIndex("Creator ID")).setCellValue(creatorId.toString());
        row.createCell(headerIndex("Created At")).setCellValue(createdAt.toString());
        row.createCell(headerIndex("Updated At")).setCellValue(updatedAt.toString());

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        QuizImportDto quiz = result.get(0);
        assertThat(quiz.id()).isEqualTo(quizId);
        assertThat(quiz.title()).isEqualTo("Quiz 1");
        assertThat(quiz.description()).isEqualTo("Desc");
        assertThat(quiz.visibility()).isNotNull();
        assertThat(quiz.difficulty()).isEqualTo(Difficulty.EASY);
        assertThat(quiz.estimatedTime()).isEqualTo(15);
        assertThat(quiz.tags()).containsExactly("tag1", "tag2");
        assertThat(quiz.category()).isEqualTo("Science");
        assertThat(quiz.creatorId()).isEqualTo(creatorId);
        assertThat(quiz.createdAt()).isEqualTo(createdAt);
        assertThat(quiz.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("parse maps all question columns")
    void parse_mapsAllQuestionColumns() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        UUID quizId = UUID.randomUUID();
        Row quizRow = quizzes.createRow(1);
        quizRow.createCell(headerIndex("Quiz ID")).setCellValue(quizId.toString());
        quizRow.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        quizRow.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        String[] questionHeaders = {
                "Quiz ID",
                "Question ID",
                "Difficulty",
                "Question Text",
                "Hint",
                "Explanation",
                "Attachment URL",
                "Sample Answer"
        };
        Sheet openSheet = createQuestionSheet(workbook, "OPEN", questionHeaders);
        Row questionRow = openSheet.createRow(1);
        UUID questionId = UUID.randomUUID();
        questionRow.createCell(headerIndex(questionHeaders, "Quiz ID")).setCellValue(quizId.toString());
        questionRow.createCell(headerIndex(questionHeaders, "Question ID")).setCellValue(questionId.toString());
        questionRow.createCell(headerIndex(questionHeaders, "Difficulty")).setCellValue("HARD");
        questionRow.createCell(headerIndex(questionHeaders, "Question Text")).setCellValue("Q1");
        questionRow.createCell(headerIndex(questionHeaders, "Hint")).setCellValue("Hint");
        questionRow.createCell(headerIndex(questionHeaders, "Explanation")).setCellValue("Explain");
        questionRow.createCell(headerIndex(questionHeaders, "Attachment URL"))
                .setCellValue("https://cdn.quizzence.com/att.png");
        questionRow.createCell(headerIndex(questionHeaders, "Sample Answer")).setCellValue("Answer");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        List<QuestionImportDto> questions = result.get(0).questions();
        assertThat(questions).hasSize(1);
        QuestionImportDto question = questions.get(0);
        assertThat(question.id()).isEqualTo(questionId);
        assertThat(question.type()).isEqualTo(QuestionType.OPEN);
        assertThat(question.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(question.questionText()).isEqualTo("Q1");
        assertThat(question.hint()).isEqualTo("Hint");
        assertThat(question.explanation()).isEqualTo("Explain");
        assertThat(question.attachmentUrl()).isEqualTo("https://cdn.quizzence.com/att.png");
        assertThat(question.content().get("answer").asText()).isEqualTo("Answer");
    }

    @Test
    @DisplayName("parse handles missing optional question columns")
    void parse_handlesMissingOptionalColumns() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        UUID quizId = UUID.randomUUID();
        Row quizRow = quizzes.createRow(1);
        quizRow.createCell(headerIndex("Quiz ID")).setCellValue(quizId.toString());
        quizRow.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        quizRow.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        String[] questionHeaders = {
                "Quiz ID",
                "Question Text",
                "Sample Answer"
        };
        Sheet openSheet = createQuestionSheet(workbook, "OPEN", questionHeaders);
        Row questionRow = openSheet.createRow(1);
        questionRow.createCell(headerIndex(questionHeaders, "Quiz ID")).setCellValue(quizId.toString());
        questionRow.createCell(headerIndex(questionHeaders, "Question Text")).setCellValue("Q1");
        questionRow.createCell(headerIndex(questionHeaders, "Sample Answer")).setCellValue("Answer");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        QuestionImportDto question = result.get(0).questions().get(0);
        assertThat(question.id()).isNull();
        assertThat(question.difficulty()).isNull();
        assertThat(question.hint()).isNull();
        assertThat(question.explanation()).isNull();
        assertThat(question.attachmentUrl()).isNull();
    }

    @Test
    @DisplayName("parse ignores extra columns")
    void parse_handlesExtraColumns() {
        Workbook workbook = new XSSFWorkbook();
        String[] quizHeaders = {
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
                "Updated At",
                "Extra Column"
        };
        Sheet quizzes = createQuizzesSheet(workbook, quizHeaders);
        UUID quizId = UUID.randomUUID();
        Row quizRow = quizzes.createRow(1);
        quizRow.createCell(headerIndex(quizHeaders, "Quiz ID")).setCellValue(quizId.toString());
        quizRow.createCell(headerIndex(quizHeaders, "Title")).setCellValue("Quiz 1");
        quizRow.createCell(headerIndex(quizHeaders, "Estimated Time (min)")).setCellValue(10);
        quizRow.createCell(headerIndex(quizHeaders, "Extra Column")).setCellValue("Ignored");

        String[] questionHeaders = {
                "Quiz ID",
                "Question Text",
                "Sample Answer",
                "Extra Question"
        };
        Sheet openSheet = createQuestionSheet(workbook, "OPEN", questionHeaders);
        Row questionRow = openSheet.createRow(1);
        questionRow.createCell(headerIndex(questionHeaders, "Quiz ID")).setCellValue(quizId.toString());
        questionRow.createCell(headerIndex(questionHeaders, "Question Text")).setCellValue("Q1");
        questionRow.createCell(headerIndex(questionHeaders, "Sample Answer")).setCellValue("Answer");
        questionRow.createCell(headerIndex(questionHeaders, "Extra Question")).setCellValue("Ignored");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Quiz 1");
        assertThat(result.get(0).questions()).hasSize(1);
    }

    @Test
    @DisplayName("parse rejects header case mismatch")
    void parse_headerCaseMismatch_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Quizzes");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Quiz ID");
        header.createCell(1).setCellValue("title");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(UUID.randomUUID().toString());

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Missing required column 'Title'");
    }

    @Test
    @DisplayName("parse handles empty question sheet")
    void parse_emptyQuestionSheet_handlesGracefully() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        createQuestionSheet(workbook, "MCQ_SINGLE", mcqHeaders());

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).questions()).isEmpty();
    }

    @Test
    @DisplayName("parse rejects malformed content")
    void parse_malformedContent_throwsException() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("maybe");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid boolean value for Correct Answer");
    }

    @Test
    @DisplayName("parse rejects missing required columns")
    void parse_missingRequiredColumns_throwsException() {
        Workbook workbook = new XSSFWorkbook();
        String[] headers = {
                "Quiz ID",
                "Title",
                "Description",
                "Visibility",
                "Difficulty",
                "Tags",
                "Category",
                "Creator ID",
                "Created At",
                "Updated At"
        };
        Sheet quizzes = createQuizzesSheet(workbook, headers);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(UUID.randomUUID().toString());
        row.createCell(headerIndex(headers, "Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex(headers, "Description")).setCellValue("Desc");
        row.createCell(headerIndex(headers, "Visibility")).setCellValue("PUBLIC");
        row.createCell(headerIndex(headers, "Difficulty")).setCellValue("EASY");
        row.createCell(headerIndex(headers, "Tags")).setCellValue("tag1");
        row.createCell(headerIndex(headers, "Category")).setCellValue("Cat");
        row.createCell(headerIndex(headers, "Creator ID")).setCellValue(UUID.randomUUID().toString());
        row.createCell(headerIndex(headers, "Created At")).setCellValue("2024-01-01T00:00:00Z");
        row.createCell(headerIndex(headers, "Updated At")).setCellValue("2024-01-02T00:00:00Z");

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Missing required column 'Estimated Time (min)'");
    }

    @Test
    @DisplayName("parse enforces max items limit")
    void parse_exceedsMaxItems_throwsException() {
        QuizImportOptions limited = QuizImportOptions.defaults(1);
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row1 = quizzes.createRow(1);
        row1.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row1.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        Row row2 = quizzes.createRow(2);
        row2.createCell(headerIndex("Title")).setCellValue("Quiz 2");
        row2.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        assertThatThrownBy(() -> parser.parse(input(workbook), limited))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("max items limit");
    }

    @Test
    @DisplayName("parse allows exactly max items")
    void parse_exactlyMaxItems_succeeds() {
        QuizImportOptions limited = QuizImportOptions.defaults(2);
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row1 = quizzes.createRow(1);
        row1.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row1.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);
        Row row2 = quizzes.createRow(2);
        row2.createCell(headerIndex("Title")).setCellValue("Quiz 2");
        row2.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), limited);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("parse rejects MATCHING questions")
    void parse_rejectsMatchingQuestions() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        createQuestionSheet(workbook, "MATCHING", new String[] {"Quiz ID", "Question Text"});

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Failed to parse XLSX import file");
    }

    @Test
    @DisplayName("parse rejects HOTSPOT questions")
    void parse_rejectsHotspotQuestions() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        createQuestionSheet(workbook, "HOTSPOT", new String[] {"Quiz ID", "Question Text"});

        assertThatThrownBy(() -> parser.parse(input(workbook), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Failed to parse XLSX import file");
    }

    @Test
    @DisplayName("parse accepts supported question types")
    void parse_acceptsSupportedTypes() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        UUID quizId = quizId(workbook);

        Sheet mcq = createQuestionSheet(workbook, "MCQ_SINGLE", mcqHeaders());
        Row mcqRow = mcq.createRow(1);
        mcqRow.createCell(headerIndex(mcqHeaders(), "Quiz ID")).setCellValue(quizId.toString());
        mcqRow.createCell(headerIndex(mcqHeaders(), "Question Text")).setCellValue("Q1");
        mcqRow.createCell(headerIndex(mcqHeaders(), "Option 1")).setCellValue("A");
        mcqRow.createCell(headerIndex(mcqHeaders(), "Option 1 Correct")).setCellValue("true");

        String[] tfHeaders = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet tf = createQuestionSheet(workbook, "TRUE_FALSE", tfHeaders);
        Row tfRow = tf.createRow(1);
        tfRow.createCell(headerIndex(tfHeaders, "Quiz ID")).setCellValue(quizId.toString());
        tfRow.createCell(headerIndex(tfHeaders, "Question Text")).setCellValue("Q2");
        tfRow.createCell(headerIndex(tfHeaders, "Correct Answer")).setCellValue("true");

        String[] openHeaders = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet open = createQuestionSheet(workbook, "OPEN", openHeaders);
        Row openRow = open.createRow(1);
        openRow.createCell(headerIndex(openHeaders, "Quiz ID")).setCellValue(quizId.toString());
        openRow.createCell(headerIndex(openHeaders, "Question Text")).setCellValue("Q3");
        openRow.createCell(headerIndex(openHeaders, "Sample Answer")).setCellValue("Answer");

        Sheet fillGap = createQuestionSheet(workbook, "FILL_GAP", fillGapHeaders());
        Row fillGapRow = fillGap.createRow(1);
        fillGapRow.createCell(headerIndex(fillGapHeaders(), "Quiz ID")).setCellValue(quizId.toString());
        fillGapRow.createCell(headerIndex(fillGapHeaders(), "Question Text")).setCellValue("Q4");
        fillGapRow.createCell(headerIndex(fillGapHeaders(), "Gap 1 Answer")).setCellValue("alpha");

        Sheet ordering = createQuestionSheet(workbook, "ORDERING", orderingHeaders());
        Row orderingRow = ordering.createRow(1);
        orderingRow.createCell(headerIndex(orderingHeaders(), "Quiz ID")).setCellValue(quizId.toString());
        orderingRow.createCell(headerIndex(orderingHeaders(), "Question Text")).setCellValue("Q5");
        orderingRow.createCell(headerIndex(orderingHeaders(), "Item 1")).setCellValue("First");

        Sheet compliance = createQuestionSheet(workbook, "COMPLIANCE", complianceHeaders());
        Row complianceRow = compliance.createRow(1);
        complianceRow.createCell(headerIndex(complianceHeaders(), "Quiz ID")).setCellValue(quizId.toString());
        complianceRow.createCell(headerIndex(complianceHeaders(), "Question Text")).setCellValue("Q6");
        complianceRow.createCell(headerIndex(complianceHeaders(), "Statement 1")).setCellValue("S1");
        complianceRow.createCell(headerIndex(complianceHeaders(), "Statement 1 Compliant")).setCellValue("yes");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        List<QuestionType> types = result.get(0).questions().stream()
                .map(QuestionImportDto::type)
                .collect(Collectors.toList());
        assertThat(types).containsExactlyInAnyOrder(
                QuestionType.MCQ_SINGLE,
                QuestionType.TRUE_FALSE,
                QuestionType.OPEN,
                QuestionType.FILL_GAP,
                QuestionType.ORDERING,
                QuestionType.COMPLIANCE
        );
    }

    @Test
    @DisplayName("parse reconstructs MCQ content")
    void parse_reconstructsMcqContent() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "MCQ_SINGLE", mcqHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(mcqHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(mcqHeaders(), "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(mcqHeaders(), "Option 1")).setCellValue("A");
        row.createCell(headerIndex(mcqHeaders(), "Option 1 Correct")).setCellValue("true");
        row.createCell(headerIndex(mcqHeaders(), "Option 2")).setCellValue("B");
        row.createCell(headerIndex(mcqHeaders(), "Option 2 Correct")).setCellValue("false");

        QuestionImportDto question = parseSingleQuestion(workbook);
        JsonNode optionsNode = question.content().get("options");

        assertThat(optionsNode).isNotNull();
        assertThat(optionsNode.size()).isEqualTo(2);
        assertThat(optionsNode.get(0).get("text").asText()).isEqualTo("A");
        assertThat(optionsNode.get(0).get("correct").asBoolean()).isTrue();
        assertThat(optionsNode.get(1).get("text").asText()).isEqualTo("B");
        assertThat(optionsNode.get(1).get("correct").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("parse reconstructs TRUE_FALSE content")
    void parse_reconstructsTrueFalseContent() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Correct Answer"};
        Sheet sheet = createQuestionSheet(workbook, "TRUE_FALSE", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Correct Answer")).setCellValue("true");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("answer").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("parse reconstructs OPEN content")
    void parse_reconstructsOpenContent() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        String[] headers = {"Quiz ID", "Question Text", "Sample Answer"};
        Sheet sheet = createQuestionSheet(workbook, "OPEN", headers);
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(headers, "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(headers, "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(headers, "Sample Answer")).setCellValue("Answer");

        QuestionImportDto question = parseSingleQuestion(workbook);

        assertThat(question.content().get("answer").asText()).isEqualTo("Answer");
    }

    @Test
    @DisplayName("parse reconstructs FILL_GAP content")
    void parse_reconstructsFillGapContent() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "FILL_GAP", fillGapHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(fillGapHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(fillGapHeaders(), "Question Text")).setCellValue("Text with gaps");
        row.createCell(headerIndex(fillGapHeaders(), "Gap 1 Answer")).setCellValue("alpha");
        row.createCell(headerIndex(fillGapHeaders(), "Gap 3 Answer")).setCellValue("gamma");

        QuestionImportDto question = parseSingleQuestion(workbook);
        JsonNode content = question.content();

        assertThat(content.get("text").asText()).isEqualTo("Text with gaps");
        JsonNode gaps = content.get("gaps");
        assertThat(gaps.size()).isEqualTo(2);
        assertThat(gaps.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(gaps.get(0).get("answer").asText()).isEqualTo("alpha");
        assertThat(gaps.get(1).get("id").asInt()).isEqualTo(3);
        assertThat(gaps.get(1).get("answer").asText()).isEqualTo("gamma");
    }

    @Test
    @DisplayName("parse reconstructs ORDERING content")
    void parse_reconstructsOrderingContent() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "ORDERING", orderingHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(orderingHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(orderingHeaders(), "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(orderingHeaders(), "Item 1")).setCellValue("First");
        row.createCell(headerIndex(orderingHeaders(), "Item 2")).setCellValue("Second");
        row.createCell(headerIndex(orderingHeaders(), "Item 3")).setCellValue("Third");

        QuestionImportDto question = parseSingleQuestion(workbook);
        JsonNode content = question.content();

        JsonNode items = content.get("items");
        JsonNode correctOrder = content.get("correctOrder");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).get("text").asText()).isEqualTo("First");
        assertThat(correctOrder.size()).isEqualTo(3);
        assertThat(correctOrder.get(0).asInt()).isEqualTo(1);
        assertThat(correctOrder.get(1).asInt()).isEqualTo(2);
        assertThat(correctOrder.get(2).asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("parse reconstructs COMPLIANCE content")
    void parse_reconstructsComplianceContent() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet sheet = createQuestionSheet(workbook, "COMPLIANCE", complianceHeaders());
        Row row = sheet.createRow(1);
        row.createCell(headerIndex(complianceHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        row.createCell(headerIndex(complianceHeaders(), "Question Text")).setCellValue("Q1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1")).setCellValue("S1");
        row.createCell(headerIndex(complianceHeaders(), "Statement 1 Compliant")).setCellValue("yes");
        row.createCell(headerIndex(complianceHeaders(), "Statement 2")).setCellValue("S2");
        row.createCell(headerIndex(complianceHeaders(), "Statement 2 Compliant")).setCellValue("no");

        QuestionImportDto question = parseSingleQuestion(workbook);
        JsonNode statements = question.content().get("statements");

        assertThat(statements.size()).isEqualTo(2);
        assertThat(statements.get(0).get("text").asText()).isEqualTo("S1");
        assertThat(statements.get(0).get("compliant").asBoolean()).isTrue();
        assertThat(statements.get(1).get("text").asText()).isEqualTo("S2");
        assertThat(statements.get(1).get("compliant").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("parse generates missing IDs")
    void parse_generatesMissingIds() {
        Workbook workbook = workbookWithQuiz(UUID.randomUUID());
        Sheet mcq = createQuestionSheet(workbook, "MCQ_SINGLE", mcqHeaders());
        Row mcqRow = mcq.createRow(1);
        mcqRow.createCell(headerIndex(mcqHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        mcqRow.createCell(headerIndex(mcqHeaders(), "Question Text")).setCellValue("Q1");
        mcqRow.createCell(headerIndex(mcqHeaders(), "Option 1")).setCellValue("A");
        mcqRow.createCell(headerIndex(mcqHeaders(), "Option 1 Correct")).setCellValue("true");
        mcqRow.createCell(headerIndex(mcqHeaders(), "Option 2")).setCellValue("B");
        mcqRow.createCell(headerIndex(mcqHeaders(), "Option 2 Correct")).setCellValue("false");

        Sheet ordering = createQuestionSheet(workbook, "ORDERING", orderingHeaders());
        Row orderingRow = ordering.createRow(1);
        orderingRow.createCell(headerIndex(orderingHeaders(), "Quiz ID")).setCellValue(quizId(workbook).toString());
        orderingRow.createCell(headerIndex(orderingHeaders(), "Question Text")).setCellValue("Q2");
        orderingRow.createCell(headerIndex(orderingHeaders(), "Item 1")).setCellValue("One");
        orderingRow.createCell(headerIndex(orderingHeaders(), "Item 2")).setCellValue("Two");

        List<QuestionImportDto> questions = parseQuestions(workbook);
        QuestionImportDto mcqQuestion = questions.stream()
                .filter(q -> q.type() == QuestionType.MCQ_SINGLE)
                .findFirst()
                .orElseThrow();
        QuestionImportDto orderingQuestion = questions.stream()
                .filter(q -> q.type() == QuestionType.ORDERING)
                .findFirst()
                .orElseThrow();

        JsonNode mcqOptions = mcqQuestion.content().get("options");
        assertThat(mcqOptions.get(0).get("id").asText()).isEqualTo("opt_1");
        assertThat(mcqOptions.get(1).get("id").asText()).isEqualTo("opt_2");

        JsonNode items = orderingQuestion.content().get("items");
        List<Integer> ids = toIntList(items, "id");
        assertThat(ids).containsExactly(1, 2);
    }

    @Test
    @DisplayName("parse reads schemaVersion from A1")
    void parse_schemaVersionInA1_usesVersion() {
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

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("parse reads schemaVersion from Metadata sheet")
    void parse_schemaVersionInMetadataSheet_usesVersion() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        Sheet metadata = workbook.createSheet("Metadata");
        Row metaRow = metadata.createRow(0);
        metaRow.createCell(0).setCellValue("schemaVersion");
        metaRow.createCell(1).setCellValue("3");

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("parse defaults schemaVersion to 1 when missing")
    void parse_withoutSchemaVersion_defaultsTo1() {
        Workbook workbook = new XSSFWorkbook();
        Sheet quizzes = createQuizzesSheet(workbook);
        Row row = quizzes.createRow(1);
        row.createCell(headerIndex("Title")).setCellValue("Quiz 1");
        row.createCell(headerIndex("Estimated Time (min)")).setCellValue(10);

        List<QuizImportDto> result = parser.parse(input(workbook), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(1);
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

    private List<Integer> toIntList(JsonNode arrayNode, String field) {
        return iterable(arrayNode).stream()
                .map(node -> node.get(field).asInt())
                .collect(Collectors.toList());
    }

    private List<JsonNode> iterable(JsonNode arrayNode) {
        return arrayNode != null && arrayNode.isArray()
                ? java.util.stream.StreamSupport.stream(arrayNode.spliterator(), false).collect(Collectors.toList())
                : List.of();
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
