package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.application.CorrectAnswerExtractor;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.application.export.impl.HtmlPrintExportRenderer;
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

@Execution(ExecutionMode.CONCURRENT)
@DisplayName("HtmlPrintExportRenderer Tests")
class HtmlPrintExportRendererTest {

    private ObjectMapper objectMapper;
    private AnswerKeyBuilder answerKeyBuilder;
    private HtmlPrintExportRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        answerKeyBuilder = new AnswerKeyBuilder(new CorrectAnswerExtractor(objectMapper));
        renderer = new HtmlPrintExportRenderer(answerKeyBuilder);
    }

    // Support Tests

    @Test
    @DisplayName("supports: returns true for HTML_PRINT")
    void supports_htmlPrint_returnsTrue() {
        // When
        boolean result = renderer.supports(ExportFormat.HTML_PRINT);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("supports: returns false for JSON_EDITABLE")
    void supports_jsonEditable_returnsFalse() {
        // When
        boolean result = renderer.supports(ExportFormat.JSON_EDITABLE);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("supports: returns false for XLSX_EDITABLE")
    void supports_xlsxEditable_returnsFalse() {
        // When
        boolean result = renderer.supports(ExportFormat.XLSX_EDITABLE);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("supports: returns false for PDF_PRINT")
    void supports_pdfPrint_returnsFalse() {
        // When
        boolean result = renderer.supports(ExportFormat.PDF_PRINT);

        // Then
        assertThat(result).isFalse();
    }

    // Basic Render Tests

    @Test
    @DisplayName("render: sets content type to text/html with charset")
    void render_setsContentTypeToHtml() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.contentType()).isEqualTo("text/html; charset=utf-8");
    }

    @Test
    @DisplayName("render: uses filename prefix with .html extension")
    void render_usesFilenamePrefixWithHtmlExtension() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "quizzes_public_202410141530");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_public_202410141530.html");
    }

    @Test
    @DisplayName("render: sets content length correctly")
    void render_setsContentLengthCorrectly() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: content length matches actual byte length")
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

    // HTML Structure Tests

    @Test
    @DisplayName("render: produces valid HTML document")
    void render_producesValidHtmlDocument() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).startsWith("<!DOCTYPE html>");
            assertThat(html).contains("<html>");
            assertThat(html).contains("<head>");
            assertThat(html).contains("<body>");
            assertThat(html).endsWith("</body></html>");
        }
    }

    @Test
    @DisplayName("render: includes UTF-8 charset meta tag")
    void render_includesUtf8Charset() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<meta charset=\"utf-8\"/>");
        }
    }

    @Test
    @DisplayName("render: includes title tag")
    void render_includesTitleTag() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<title>Quizzes Export</title>");
        }
    }

    @Test
    @DisplayName("render: includes CSS styles")
    void render_includesCssStyles() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<style>");
            assertThat(html).contains("</style>");
            assertThat(html).contains("font-family");
            assertThat(html).contains(".question");
            assertThat(html).contains(".answer-key");
        }
    }

    // PrintOptions Tests - Cover

    @Test
    @DisplayName("render: includes cover when option is true")
    void render_includeCover_true_rendersCover() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        PrintOptions options = new PrintOptions(true, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<div class=\"cover\">");
            assertThat(html).contains("Jump to Answer Key");
            assertThat(html).contains("href=\"#answer-key\"");
        }
    }

    @Test
    @DisplayName("render: excludes cover when option is false")
    void render_includeCover_false_excludesCover() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"cover\">");
        }
    }

    // PrintOptions Tests - Metadata

    @Test
    @DisplayName("render: includes quiz metadata when option is true")
    void render_includeMetadata_true_rendersMetadata() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Test Quiz", "Description", Visibility.PUBLIC,
                Difficulty.HARD, 30, List.of("java", "spring"),
                "Programming", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<h2>Test Quiz</h2>");
            assertThat(html).contains("<p>Description</p>");
        }
    }

    @Test
    @DisplayName("render: excludes quiz metadata when option is false")
    void render_includeMetadata_false_excludesMetadata() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Test Quiz", "Description", Visibility.PUBLIC,
                Difficulty.HARD, 30, List.of("java", "spring"),
                "Programming", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            // Metadata section should not appear for single quiz when metadata is false
            assertThat(html).doesNotContain("Category: Programming");
            assertThat(html).doesNotContain("Time: 30 min");
        }
    }

    @Test
    @DisplayName("render: shows quiz header for multiple quizzes even without metadata option")
    void render_multipleQuizzes_showsHeaders() throws Exception {
        // Given
        QuizExportDto quiz1 = createMinimalQuiz();
        QuizExportDto quiz2 = createMinimalQuiz();
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz1, quiz2), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<h2>Test Quiz</h2>");
        }
    }

    // PrintOptions Tests - Hints

    @Test
    @DisplayName("render: includes hints when option is true")
    void render_includeHints_true_rendersHints() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is this true?", createTrueFalseContent(true),
                "Think about the basics", null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, true, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<div class=\"hint\">");
            assertThat(html).contains("💡");
            assertThat(html).contains("Hint:");
            assertThat(html).contains("Think about the basics");
        }
    }

    @Test
    @DisplayName("render: excludes hints when option is false")
    void render_includeHints_false_excludesHints() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is this true?", createTrueFalseContent(true),
                "Think about the basics", null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"hint\">");
            assertThat(html).doesNotContain("Think about the basics");
        }
    }

    // PrintOptions Tests - Explanations

    @Test
    @DisplayName("render: includes explanations when option is true")
    void render_includeExplanations_true_rendersExplanations() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is this true?", createTrueFalseContent(true),
                null, "This is true because...", null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, true, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<div class=\"explanation\">");
            assertThat(html).contains("ℹ️");
            assertThat(html).contains("Explanation:");
            assertThat(html).contains("This is true because...");
        }
    }

    @Test
    @DisplayName("render: excludes explanations when option is false")
    void render_includeExplanations_false_excludesExplanations() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is this true?", createTrueFalseContent(true),
                null, "This is true because...", null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"explanation\">");
            assertThat(html).doesNotContain("This is true because...");
        }
    }

    // PrintOptions Tests - Answer Pages

    @Test
    @DisplayName("render: includes page break before answers when option is true")
    void render_answersOnSeparatePages_true_includesPageBreak() throws Exception {
        // Given
        QuestionExportDto question = createTrueFalseQuestion("Is this true?", true);
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, true, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<div class=\"page-break\"></div>");
            // Should appear before answer key
            int pageBreakIdx = html.indexOf("<div class=\"page-break\">");
            int answerKeyIdx = html.indexOf("id=\"answer-key\"");
            assertThat(pageBreakIdx).isLessThan(answerKeyIdx);
        }
    }

    @Test
    @DisplayName("render: excludes page break when option is false")
    void render_answersOnSeparatePages_false_excludesPageBreak() throws Exception {
        // Given
        QuestionExportDto question = createTrueFalseQuestion("Is this true?", true);
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"page-break\">");
        }
    }

    // PrintOptions Tests - Grouping

    @Test
    @DisplayName("render: groups questions by type when option is true")
    void render_groupQuestionsByType_true_groupsQuestions() throws Exception {
        // Given
        QuestionExportDto q1 = createTrueFalseQuestion("Q1", true);
        QuestionExportDto q2 = createMcqSingleQuestion("Q2");
        QuestionExportDto q3 = createTrueFalseQuestion("Q3", false);
        
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2, q3));
        PrintOptions options = new PrintOptions(false, false, false, false, false, true);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<div class=\"type-section\">");
            assertThat(html).contains("<h3>True/False Questions</h3>");
            assertThat(html).contains("<h3>Multiple Choice (Single Answer) Questions</h3>");
        }
    }

    @Test
    @DisplayName("render: renders questions sequentially when option is false")
    void render_groupQuestionsByType_false_sequentialQuestions() throws Exception {
        // Given
        QuestionExportDto q1 = createTrueFalseQuestion("Q1", true);
        QuestionExportDto q2 = createMcqSingleQuestion("Q2");
        QuestionExportDto q3 = createTrueFalseQuestion("Q3", false);
        
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2, q3));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"type-section\">");
            assertThat(html).doesNotContain("<h3>True/False Questions</h3>");
        }
    }

    // Question Type Rendering Tests

    @Test
    @DisplayName("render: renders TRUE_FALSE question correctly")
    void render_trueFalseQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createTrueFalseQuestion("Is Java object-oriented?", true);
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Is Java object-oriented?");
            assertThat(html).contains("<strong>A.</strong> True");
            assertThat(html).contains("<strong>B.</strong> False");
        }
    }

    @Test
    @DisplayName("render: renders MCQ_SINGLE question correctly")
    void render_mcqSingleQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createMcqSingleQuestion("What is 2+2?");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("What is 2+2?");
            assertThat(html).contains("<strong>A.</strong> 3");
            assertThat(html).contains("<strong>B.</strong> 4");
            assertThat(html).contains("<strong>C.</strong> 5");
            assertThat(html).contains("<strong>B</strong>"); // Answer key
        }
    }

    @Test
    @DisplayName("render: renders MCQ_MULTI question correctly")
    void render_mcqMultiQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createMcqMultiQuestion("Select programming languages");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Select programming languages");
            assertThat(html).contains("<strong>A.</strong> Java");
            assertThat(html).contains("<strong>B.</strong> Python");
            assertThat(html).contains("<strong>C.</strong> HTML");
            assertThat(html).contains("<strong>A</strong>, <strong>B</strong>"); // Answer key
        }
    }

    @Test
    @DisplayName("render: renders FILL_GAP question correctly")
    void render_fillGapQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createFillGapQuestion("Complete the sentence");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Complete the sentence");
            assertThat(html).contains("<strong>1.</strong> _________________");
            assertThat(html).contains("<strong>2.</strong> _________________");
        }
    }

    @Test
    @DisplayName("render: uses content.text for FILL_GAP question display (Issue #165)")
    void render_fillGapQuestion_usesContentText() throws Exception {
        // Given - FILL_GAP question with actual prompt in content.text
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", "One of the main functions of NAT is to allow devices on the ___ network to access the Internet.");
        ArrayNode gaps = objectMapper.createArrayNode();
        gaps.add(objectMapper.createObjectNode().put("id", 1).put("answer", "private"));
        content.set("gaps", gaps);
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.FILL_GAP, Difficulty.MEDIUM,
                "Complete the sentence with the missing word(s)", // Generic text
                content, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            // Should display the actual prompt with underscores
            assertThat(html).contains("One of the main functions of NAT");
            assertThat(html).contains("___ network");
            // Should NOT display the generic question text in the header
            int headerIdx = html.indexOf("class=\"question-header\"");
            int nextHeaderIdx = html.indexOf("class=\"question-header\"", headerIdx + 1);
            String firstQuestionHeader = nextHeaderIdx > 0 
                ? html.substring(headerIdx, nextHeaderIdx)
                : html.substring(headerIdx);
            assertThat(firstQuestionHeader).doesNotContain("Complete the sentence with the missing word(s)");
        }
    }

    @Test
    @DisplayName("render: falls back to questionText when content.text is missing for FILL_GAP")
    void render_fillGapQuestion_fallsBackToQuestionText() throws Exception {
        // Given - FILL_GAP question without content.text
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode gaps = objectMapper.createArrayNode();
        gaps.add(objectMapper.createObjectNode().put("id", 1).put("answer", "answer"));
        content.set("gaps", gaps);
        // Note: no "text" field in content
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.FILL_GAP, Difficulty.EASY,
                "Complete the sentence",
                content, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should fall back to questionText
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Complete the sentence");
        }
    }

    @Test
    @DisplayName("render: renders ORDERING question correctly")
    void render_orderingQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createOrderingQuestion("Order these steps");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Order these steps");
            assertThat(html).contains("First");
            assertThat(html).contains("Second");
            // Items should be numbered but shuffled, so we don't assert on specific order
        }
    }

    @Test
    @DisplayName("render: renders MATCHING question correctly")
    void render_matchingQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createMatchingQuestion("Match countries with capitals");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Match countries with capitals");
            assertThat(html).contains("class=\"matching-columns\"");
            assertThat(html).contains("<h4>Column 1</h4>");
            assertThat(html).contains("<h4>Column 2</h4>");
            assertThat(html).contains("<strong>1.</strong> France");
            assertThat(html).contains("<strong>A.</strong> Paris");
        }
    }

    @Test
    @DisplayName("render: renders HOTSPOT question correctly")
    void render_hotspotQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createHotspotQuestion("Select the capital city");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Select the capital city");
            assertThat(html).contains("Image: /images/map.png");
        }
    }

    @Test
    @DisplayName("render: renders COMPLIANCE question correctly")
    void render_complianceQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createComplianceQuestion("Check compliance");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Check compliance");
            assertThat(html).contains("Statement 1");
            assertThat(html).contains("Statement 2");
        }
    }

    @Test
    @DisplayName("render: renders OPEN question correctly")
    void render_openQuestion_rendersCorrectly() throws Exception {
        // Given
        QuestionExportDto question = createOpenQuestion("Explain polymorphism");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Explain polymorphism");
            assertThat(html).contains("<em>Answer:</em>");
            assertThat(html).contains("_____________________________________________________________________");
        }
    }

    // Answer Key Tests

    @Test
    @DisplayName("render: includes answer key section")
    void render_includesAnswerKeySection() throws Exception {
        // Given
        QuestionExportDto question = createTrueFalseQuestion("Is this true?", true);
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<section class=\"answer-key\" id=\"answer-key\">");
            assertThat(html).contains("<h2>Answer Key</h2>");
        }
    }

    @Test
    @DisplayName("render: answer key shows numbered entries")
    void render_answerKeyShowsNumberedEntries() throws Exception {
        // Given
        QuestionExportDto q1 = createTrueFalseQuestion("Q1", true);
        QuestionExportDto q2 = createTrueFalseQuestion("Q2", false);
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("<strong>1.</strong>");
            assertThat(html).contains("<strong>2.</strong>");
            assertThat(html).contains("True"); // Answer 1
            assertThat(html).contains("False"); // Answer 2
        }
    }

    @Test
    @DisplayName("render: answer key shows TRUE_FALSE answers correctly")
    void render_answerKey_trueFalse_correct() throws Exception {
        // Given
        QuestionExportDto question = createTrueFalseQuestion("Is this true?", true);
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("True");
        }
    }

    // HTML Escaping Tests

    @Test
    @DisplayName("render: escapes HTML special characters in quiz title")
    void render_escapesHtmlInQuizTitle() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz with <script>alert('XSS')</script> & \"quotes\"",
                "Description", Visibility.PUBLIC, Difficulty.EASY, 10,
                List.of(), "Category", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).contains("&amp;");
            assertThat(html).contains("&quot;");
            assertThat(html).doesNotContain("<script>");
        }
    }

    @Test
    @DisplayName("render: escapes HTML in question text")
    void render_escapesHtmlInQuestionText() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is <b>bold</b> & 'italic' correct?",
                createTrueFalseContent(true), null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
            assertThat(html).contains("&amp;");
            assertThat(html).contains("&#39;");
        }
    }

    @Test
    @DisplayName("render: escapes HTML in MCQ options")
    void render_escapesHtmlInMcqOptions() throws Exception {
        // Given
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        options.add(objectMapper.createObjectNode()
                .put("id", "1")
                .put("text", "<script>alert('XSS')</script>")
                .put("correct", true));
        content.set("options", options);
        content.put("correctOptionId", "1");
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.MCQ_SINGLE, Difficulty.EASY,
                "Select option", content, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).doesNotContain("<script>");
        }
    }

    @Test
    @DisplayName("render: escapes HTML in quiz content")
    void render_escapesHtmlInCategoryAndTags() throws Exception {
        // Given (metadata removed, but HTML escaping still important for quiz title/description)
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Test <script>Quiz</script>", "Description & more", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of("java<script>", "spring&boot"),
                "Web<dev>", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).contains("&amp; more");
        }
    }

    // Edge Cases

    @Test
    @DisplayName("render: handles empty quiz list")
    void render_emptyQuizList_producesValidHtml() throws Exception {
        // Given
        ExportPayload payload = new ExportPayload(new ArrayList<>(), PrintOptions.defaults(), "empty");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).startsWith("<!DOCTYPE html>");
            assertThat(html).contains("id=\"answer-key\"");
            assertThat(html).contains("<h2>Answer Key</h2>");
        }
    }

    @Test
    @DisplayName("render: handles quiz with no questions")
    void render_quizWithNoQuestions_producesValidHtml() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).startsWith("<!DOCTYPE html>");
            assertThat(html).contains("id=\"answer-key\"");
            assertThat(html).contains("<h2>Answer Key</h2>");
        }
    }

    @Test
    @DisplayName("render: handles question with null content")
    void render_questionWithNullContent_handlesGracefully() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Question text", null, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should not throw
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Question text");
        }
    }

    @Test
    @DisplayName("render: handles quiz with null description")
    void render_quizWithNullDescription_handlesGracefully() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Title", null, Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), "Cat", UUID.randomUUID(),
                new ArrayList<>(), Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should not throw
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Title");
        }
    }

    @Test
    @DisplayName("render: handles quiz with null tags")
    void render_quizWithNullTags_handlesGracefully() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Title", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, null, "Cat", UUID.randomUUID(),
                new ArrayList<>(), Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should not throw
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Title");
        }
    }

    @Test
    @DisplayName("render: handles quiz with null category")
    void render_quizWithNullCategory_handlesGracefully() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Title", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), null, UUID.randomUUID(),
                new ArrayList<>(), Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should not throw
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("Title");
        }
    }

    @Test
    @DisplayName("render: handles question with blank hint")
    void render_questionWithBlankHint_excludesHint() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is this true?", createTrueFalseContent(true),
                "   ", null, null // Blank hint
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, true, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"hint\">");
        }
    }

    @Test
    @DisplayName("render: handles question with blank explanation")
    void render_questionWithBlankExplanation_excludesExplanation() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                "Is this true?", createTrueFalseContent(true),
                null, "   ", null // Blank explanation
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, true, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).doesNotContain("<div class=\"explanation\">");
        }
    }

    @Test
    @DisplayName("render: content supplier can be invoked multiple times")
    void render_contentSupplierReusable() throws Exception {
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
    @DisplayName("render: handles unicode characters correctly")
    void render_unicodeCharacters_preservedCorrectly() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "测试 тест テスト", "Émoji: 🎉 ✅",
                Visibility.PUBLIC, Difficulty.EASY, 10, List.of("日本語"),
                "中文", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        PrintOptions options = new PrintOptions(false, true, false, false, false, false);
        ExportPayload payload = new ExportPayload(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String html = new String(is.readAllBytes());
            assertThat(html).contains("测试"); // Title
            assertThat(html).contains("🎉");  // Description emoji
            assertThat(html).contains("тест"); // Title cyrillic
        }
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
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.TRUE_FALSE, Difficulty.EASY,
                text, createTrueFalseContent(answer), null, null, null
        );
    }

    private JsonNode createTrueFalseContent(boolean answer) {
        return objectMapper.createObjectNode().put("answer", answer);
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
        ArrayNode correctIds = objectMapper.createArrayNode();
        correctIds.add("1");
        correctIds.add("2");
        content.set("correctOptionIds", correctIds);
        
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

    private QuestionExportDto createOpenQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("answer", "Sample answer");
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                text, content, null, null, null
        );
    }
}

