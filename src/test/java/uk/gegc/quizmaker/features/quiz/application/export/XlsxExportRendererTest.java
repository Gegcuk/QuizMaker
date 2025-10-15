package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.application.export.impl.XlsxExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for XlsxExportRenderer.
 * 
 * Note: Tests verify rendering success and output characteristics rather than parsing
 * the generated XLSX to avoid Apache POI version compatibility issues.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("XlsxExportRenderer Tests")
class XlsxExportRendererTest {

    private ObjectMapper objectMapper;
    private XlsxExportRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        renderer = new XlsxExportRenderer();
    }

    // Support Tests

    @Test
    @DisplayName("supports: returns true for XLSX_EDITABLE")
    void supports_xlsxEditable_returnsTrue() {
        assertThat(renderer.supports(ExportFormat.XLSX_EDITABLE)).isTrue();
    }

    @Test
    @DisplayName("supports: returns false for JSON_EDITABLE")
    void supports_jsonEditable_returnsFalse() {
        assertThat(renderer.supports(ExportFormat.JSON_EDITABLE)).isFalse();
    }

    @Test
    @DisplayName("supports: returns false for HTML_PRINT")
    void supports_htmlPrint_returnsFalse() {
        assertThat(renderer.supports(ExportFormat.HTML_PRINT)).isFalse();
    }

    @Test
    @DisplayName("supports: returns false for PDF_PRINT")
    void supports_pdfPrint_returnsFalse() {
        assertThat(renderer.supports(ExportFormat.PDF_PRINT)).isFalse();
    }

    // Basic Rendering Tests

    @Test
    @DisplayName("render: sets content type to XLSX")
    void render_setsContentTypeToXlsx() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    @DisplayName("render: uses filename prefix with .xlsx extension")
    void render_usesFilenamePrefixWithXlsxExtension() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "quizzes_public_202410141530");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_public_202410141530.xlsx");
    }

    @Test
    @DisplayName("render: sets positive content length")
    void render_setsPositiveContentLength() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: content length matches actual bytes")
    void render_contentLengthMatchesActualBytes() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            byte[] bytes = is.readAllBytes();
            assertThat(file.contentLength()).isEqualTo(bytes.length);
        }
    }

    @Test
    @DisplayName("render: produces valid XLSX file (ZIP format)")
    void render_producesValidXlsxFile() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - XLSX files are ZIP archives starting with PK
        try (InputStream is = file.contentSupplier().get()) {
            byte[] header = new byte[2];
            is.read(header);
            assertThat(header).isEqualTo(new byte[]{'P', 'K'});
        }
    }

    @Test
    @DisplayName("render: produces non-trivial content")
    void render_producesNonTrivialContent() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - XLSX files have significant overhead
        try (InputStream is = file.contentSupplier().get()) {
            byte[] content = is.readAllBytes();
            assertThat(content.length).isGreaterThan(1000);
        }
    }

    // Rendering with Different Quiz Types

    @Test
    @DisplayName("render: handles quiz with full metadata")
    void render_quizWithFullMetadata_succeeds() {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "My Test Quiz", "Quiz Description",
                Visibility.PUBLIC, Difficulty.HARD, 45,
                List.of("java", "spring", "testing"), "Programming",
                UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles multiple quizzes")
    void render_multipleQuizzes_succeeds() {
        // Given
        List<QuizExportDto> quizzes = List.of(
                createMinimalQuiz(),
                createMinimalQuiz(),
                createMinimalQuiz()
        );
        ExportPayload payload = new ExportPayload(quizzes, PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles quiz with null optional fields")
    void render_quizWithNullFields_succeeds() {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Title", null, Visibility.PUBLIC,
                Difficulty.EASY, 10, null, null, null,
                new ArrayList<>(), Instant.now(), Instant.now()
        );
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    // Rendering with Different Question Types

    @Test
    @DisplayName("render: handles TRUE_FALSE questions")
    void render_trueFalseQuestions_succeeds() {
        // Given
        QuestionExportDto question = createTrueFalseQuestion("Is Java fun?", true);
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles OPEN questions")
    void render_openQuestions_succeeds() {
        // Given
        QuestionExportDto question = createOpenQuestion("Explain polymorphism", "Polymorphism allows...");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles MCQ_SINGLE questions")
    void render_mcqSingleQuestions_succeeds() {
        // Given
        QuestionExportDto question = createMcqSingleQuestion("What is 2+2?");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles MCQ_MULTI questions")
    void render_mcqMultiQuestions_succeeds() {
        // Given
        QuestionExportDto question = createMcqMultiQuestion("Select programming languages");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles FILL_GAP questions")
    void render_fillGapQuestions_succeeds() {
        // Given
        QuestionExportDto question = createFillGapQuestion("Complete the gaps");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles ORDERING questions")
    void render_orderingQuestions_succeeds() {
        // Given
        QuestionExportDto question = createOrderingQuestion("Order these steps");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles MATCHING questions")
    void render_matchingQuestions_succeeds() {
        // Given
        QuestionExportDto question = createMatchingQuestion("Match countries");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles HOTSPOT questions")
    void render_hotspotQuestions_succeeds() {
        // Given
        QuestionExportDto question = createHotspotQuestion("Select region");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles COMPLIANCE questions")
    void render_complianceQuestions_succeeds() {
        // Given
        QuestionExportDto question = createComplianceQuestion("Check compliance");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles mixed question types")
    void render_mixedQuestionTypes_succeeds() {
        // Given
        List<QuestionExportDto> questions = List.of(
                createTrueFalseQuestion("Q1", true),
                createOpenQuestion("Q2", "Answer 2"),
                createMcqSingleQuestion("Q3"),
                createFillGapQuestion("Q4"),
                createOrderingQuestion("Q5")
        );
        QuizExportDto quiz = createQuizWithQuestions(questions);
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles questions with hints and explanations")
    void render_questionsWithHintsAndExplanations_succeeds() {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Test question", createOpenQuestion("Test", "Answer").content(),
                "This is a hint", "This is an explanation", "http://example.com/attachment.pdf"
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles question with null content")
    void render_questionWithNullContent_succeeds() {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Test question", null, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles multiple questions from multiple quizzes")
    void render_multipleQuestionsFromMultipleQuizzes_succeeds() {
        // Given
        QuestionExportDto q1 = createTrueFalseQuestion("Q1", true);
        QuestionExportDto q2 = createOpenQuestion("Q2", "Answer 2");
        QuestionExportDto q3 = createMcqSingleQuestion("Q3");
        
        QuizExportDto quiz1 = createQuizWithQuestions(List.of(q1, q2));
        QuizExportDto quiz2 = createQuizWithQuestions(List.of(q3));
        
        ExportPayload payload = new ExportPayload(List.of(quiz1, quiz2), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    // Edge Cases

    @Test
    @DisplayName("render: handles empty quiz list")
    void render_emptyQuizList_succeeds() {
        // Given
        ExportPayload payload = new ExportPayload(new ArrayList<>(), PrintOptions.defaults(), "empty");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles quiz with no questions")
    void render_quizWithNoQuestions_succeeds() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: content supplier is reusable")
    void render_contentSupplierIsReusable() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        byte[] content1;
        byte[] content2;
        
        try (InputStream is = file.contentSupplier().get()) {
            content1 = is.readAllBytes();
        }
        try (InputStream is = file.contentSupplier().get()) {
            content2 = is.readAllBytes();
        }
        
        assertThat(content1).isEqualTo(content2);
    }

    @Test
    @DisplayName("render: filename includes filter context")
    void render_filenameIncludesFilterContext() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(
                List.of(quiz),
                PrintOptions.defaults(),
                "quizzes_me_202410141530_cat2_tag3_medium"
        );

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_me_202410141530_cat2_tag3_medium.xlsx");
        assertThat(file.filename()).contains("cat2");
        assertThat(file.filename()).contains("tag3");
        assertThat(file.filename()).contains("medium");
    }

    @Test
    @DisplayName("render: ignores PrintOptions (XLSX format doesn't use them)")
    void render_ignoresPrintOptions() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        
        ExportPayload payloadDefaults = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");
        ExportPayload payloadTeacher = new ExportPayload(List.of(quiz), PrintOptions.teacherEdition(), "test");

        // When
        ExportFile fileDefaults = renderer.render(payloadDefaults);
        ExportFile fileTeacher = renderer.render(payloadTeacher);

        // Then - both should produce similar content (PrintOptions don't affect XLSX structure)
        // File sizes should be very close (within 5%)
        assertThat(fileDefaults.contentLength()).isCloseTo(
                fileTeacher.contentLength(),
                org.assertj.core.data.Percentage.withPercentage(5)
        );
    }

    @Test
    @DisplayName("render: handles quiz with many questions")
    void render_manyQuestions_succeeds() {
        // Given
        List<QuestionExportDto> questions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            questions.add(createOpenQuestion("Question " + i, "Answer " + i));
        }
        QuizExportDto quiz = createQuizWithQuestions(questions);
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: larger output with more quizzes")
    void render_moreQuizzes_largerOutput() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        
        ExportPayload payloadSingle = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");
        ExportPayload payloadMultiple = new ExportPayload(
                List.of(quiz, quiz, quiz, quiz, quiz),
                PrintOptions.defaults(),
                "test"
        );

        // When
        ExportFile fileSingle = renderer.render(payloadSingle);
        ExportFile fileMultiple = renderer.render(payloadMultiple);

        // Then - more quizzes should produce larger output
        assertThat(fileMultiple.contentLength()).isGreaterThan(fileSingle.contentLength());
    }

    // Helper Methods

    private QuizExportDto createMinimalQuiz() {
        return new QuizExportDto(
                UUID.randomUUID(), "Test Quiz", "Description",
                Visibility.PUBLIC, Difficulty.EASY, 10,
                List.of(), "General", UUID.randomUUID(),
                new ArrayList<>(), Instant.now(), Instant.now()
        );
    }

    private QuizExportDto createQuizWithQuestions(List<QuestionExportDto> questions) {
        return new QuizExportDto(
                UUID.randomUUID(), "Test Quiz", "Description",
                Visibility.PUBLIC, Difficulty.EASY, 10,
                List.of(), "General", UUID.randomUUID(),
                questions, Instant.now(), Instant.now()
        );
    }

    private QuestionExportDto createTrueFalseQuestion(String text, boolean answer) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("answer", answer);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createOpenQuestion(String text, String answer) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("answer", answer);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createMcqSingleQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        options.add(objectMapper.createObjectNode().put("id", "1").put("text", "3").put("correct", false));
        options.add(objectMapper.createObjectNode().put("id", "2").put("text", "4").put("correct", true));
        options.add(objectMapper.createObjectNode().put("id", "3").put("text", "5").put("correct", false));
        content.set("options", options);
        content.put("correctOptionId", "2");
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.MCQ_SINGLE, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createMcqMultiQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        options.add(objectMapper.createObjectNode().put("id", "1").put("text", "Java").put("correct", true));
        options.add(objectMapper.createObjectNode().put("id", "2").put("text", "Python").put("correct", true));
        options.add(objectMapper.createObjectNode().put("id", "3").put("text", "HTML").put("correct", false));
        content.set("options", options);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.MCQ_MULTI, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createFillGapQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode gaps = objectMapper.createArrayNode();
        gaps.add(objectMapper.createObjectNode().put("id", "1").put("answer", "first"));
        gaps.add(objectMapper.createObjectNode().put("id", "2").put("answer", "second"));
        content.set("gaps", gaps);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.FILL_GAP, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createOrderingQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("id", "1").put("text", "First").put("order", 1));
        items.add(objectMapper.createObjectNode().put("id", "2").put("text", "Second").put("order", 2));
        content.set("items", items);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.ORDERING, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createMatchingQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode left = objectMapper.createArrayNode();
        left.add(objectMapper.createObjectNode().put("id", "1").put("text", "France").put("matchId", "A"));
        ArrayNode right = objectMapper.createArrayNode();
        right.add(objectMapper.createObjectNode().put("id", "A").put("text", "Paris"));
        content.set("left", left);
        content.set("right", right);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.MATCHING, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createHotspotQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("imageUrl", "/images/map.png");
        ArrayNode regions = objectMapper.createArrayNode();
        regions.add(objectMapper.createObjectNode()
                .put("id", 1)
                .put("x", 100)
                .put("y", 200)
                .put("width", 50)
                .put("height", 50)
                .put("correct", true));
        content.set("regions", regions);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.HOTSPOT, Difficulty.EASY,
                text, content, null, null, null
        );
    }

    private QuestionExportDto createComplianceQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode statements = objectMapper.createArrayNode();
        statements.add(objectMapper.createObjectNode().put("id", "1").put("text", "Statement 1").put("compliant", true));
        statements.add(objectMapper.createObjectNode().put("id", "2").put("text", "Statement 2").put("compliant", false));
        content.set("statements", statements);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.COMPLIANCE, Difficulty.EASY,
                text, content, null, null, null
        );
    }
}
