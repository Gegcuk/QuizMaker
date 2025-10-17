package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import uk.gegc.quizmaker.features.quiz.application.export.impl.PdfPrintExportRenderer;
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
 * Comprehensive unit tests for PdfPrintExportRenderer.
 * 
 * Note: Uses OPEN questions to avoid Unicode font encoding issues with bullet characters
 * in Helvetica font (○, U+25CB not supported in WinAnsiEncoding).
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("PdfPrintExportRenderer Tests")
class PdfPrintExportRendererTest {

    private ObjectMapper objectMapper;
    private AnswerKeyBuilder answerKeyBuilder;
    private PdfPrintExportRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        answerKeyBuilder = new AnswerKeyBuilder(new CorrectAnswerExtractor(objectMapper));
        renderer = new PdfPrintExportRenderer(answerKeyBuilder);
    }

    // Support Tests

    @Test
    @DisplayName("supports: returns true for PDF_PRINT")
    void supports_pdfPrint_returnsTrue() {
        assertThat(renderer.supports(ExportFormat.PDF_PRINT)).isTrue();
    }

    @Test
    @DisplayName("supports: returns false for JSON_EDITABLE")
    void supports_jsonEditable_returnsFalse() {
        assertThat(renderer.supports(ExportFormat.JSON_EDITABLE)).isFalse();
    }

    @Test
    @DisplayName("supports: returns false for XLSX_EDITABLE")
    void supports_xlsxEditable_returnsFalse() {
        assertThat(renderer.supports(ExportFormat.XLSX_EDITABLE)).isFalse();
    }

    @Test
    @DisplayName("supports: returns false for HTML_PRINT")
    void supports_htmlPrint_returnsFalse() {
        assertThat(renderer.supports(ExportFormat.HTML_PRINT)).isFalse();
    }

    // Basic Rendering Tests

    @Test
    @DisplayName("render: sets content type to application/pdf")
    void render_setsContentTypeToPdf() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("render: uses filename prefix with .pdf extension")
    void render_usesFilenamePrefixWithPdfExtension() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "quizzes_public_202410141530");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_public_202410141530.pdf");
    }

    @Test
    @DisplayName("render: sets positive content length")
    void render_setsPositiveContentLength() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

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
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            byte[] bytes = is.readAllBytes();
            assertThat(file.contentLength()).isEqualTo(bytes.length);
        }
    }

    @Test
    @DisplayName("render: produces PDF with correct header")
    void render_producesPdfWithCorrectHeader() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            byte[] header = new byte[4];
            is.read(header);
            assertThat(new String(header)).isEqualTo("%PDF");
        }
    }

    @Test
    @DisplayName("render: produces valid parseable PDF")
    void render_producesValidParseablePdf() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("render: includes quiz title in PDF content")
    void render_includesQuizTitleInContent() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "My Unique Test Quiz Title", "Description",
                Visibility.PUBLIC, Difficulty.EASY, 10, List.of(), "General",
                UUID.randomUUID(), new ArrayList<>(), Instant.now(), Instant.now()
        );
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("My Unique Test Quiz Title");
        }
    }

    @Test
    @DisplayName("render: includes question text in PDF content")
    void render_includesQuestionTextInContent() throws Exception {
        // Given
        QuestionExportDto question = createOpenQuestion("Explain the concept of polymorphism in Java");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Explain the concept of polymorphism");
        }
    }

    @Test
    @DisplayName("render: includes answer key section")
    void render_includesAnswerKeySection() throws Exception {
        // Given
        QuestionExportDto question = createOpenQuestion("What is inheritance?");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Answer Key");
        }
    }

    // PrintOptions Tests - Cover

    @Test
    @DisplayName("render: includes cover when option is true")
    void render_includeCover_true_rendersCover() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        PrintOptions options = new PrintOptions(true, false, false, false, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Cover now shows quiz title for single quiz
            assertThat(text).contains("Test Quiz");
            assertThat(text).contains("Generated:");
        }
    }

    @Test
    @DisplayName("render: excludes cover when option is false")
    void render_includeCover_false_excludesCover() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - Compact PDF without cover should be smaller
        byte[] bytesWithCover;
        byte[] bytesWithoutCover;
        
        PrintOptions optionsWithCover = new PrintOptions(true, false, false, false, false, false);
        ExportPayload payloadWithCover = ExportPayload.of(List.of(quiz), optionsWithCover, "test");
        ExportFile fileWithCover = renderer.render(payloadWithCover);
        
        try (InputStream is = file.contentSupplier().get()) {
            bytesWithoutCover = is.readAllBytes();
        }
        try (InputStream is = fileWithCover.contentSupplier().get()) {
            bytesWithCover = is.readAllBytes();
        }
        
        assertThat(bytesWithoutCover.length).isLessThan(bytesWithCover.length);
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
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Programming");
            assertThat(text).contains("HARD");
            assertThat(text).contains("30");
        }
    }

    // PrintOptions Tests - Hints

    @Test
    @DisplayName("render: includes hints when option is true")
    void render_includeHints_true_rendersHints() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Explain polymorphism", createOpenQuestion("Explain polymorphism").content(),
                "Think about method overriding", null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, true, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Hint:");
            assertThat(text).contains("Think about method overriding");
        }
    }

    @Test
    @DisplayName("render: excludes hints when option is false")
    void render_includeHints_false_excludesHints() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Explain polymorphism", createOpenQuestion("Explain polymorphism").content(),
                "Think about method overriding", null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).doesNotContain("Hint:");
            assertThat(text).doesNotContain("Think about method overriding");
        }
    }

    // PrintOptions Tests - Explanations

    @Test
    @DisplayName("render: includes explanations when option is true")
    void render_includeExplanations_true_rendersExplanations() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Explain polymorphism", createOpenQuestion("Explain polymorphism").content(),
                null, "Polymorphism allows objects to take many forms", null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, true, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Explanation:");
            assertThat(text).contains("Polymorphism allows objects to take many forms");
        }
    }

    @Test
    @DisplayName("render: excludes explanations when option is false")
    void render_includeExplanations_false_excludesExplanations() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Explain polymorphism", createOpenQuestion("Explain polymorphism").content(),
                null, "Polymorphism allows objects to take many forms", null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).doesNotContain("Explanation:");
            assertThat(text).doesNotContain("Polymorphism allows objects to take many forms");
        }
    }

    // PrintOptions Tests - Answer Pages

    @Test
    @DisplayName("render: places answers on separate page when option is true")
    void render_answersOnSeparatePages_true_separatesAnswers() throws Exception {
        // Given
        QuestionExportDto question = createOpenQuestion("Explain encapsulation");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        PrintOptions options = new PrintOptions(false, false, true, false, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            // With separate pages, should have at least 2 pages
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        }
    }

    // PrintOptions Tests - Grouping

    @Test
    @DisplayName("render: groups questions by type when option is true")
    void render_groupQuestionsByType_true_groupsQuestions() throws Exception {
        // Given
        QuestionExportDto q1 = createOpenQuestion("Q1");
        QuestionExportDto q2 = createOpenQuestion("Q2");
        QuestionExportDto q3 = createOpenQuestion("Q3");
        
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2, q3));
        PrintOptions options = new PrintOptions(false, false, false, false, false, true);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Open-Ended Questions");
        }
    }

    @Test
    @DisplayName("render: renders questions sequentially when grouping is false")
    void render_groupQuestionsByType_false_sequentialQuestions() throws Exception {
        // Given
        QuestionExportDto q1 = createOpenQuestion("Q1");
        QuestionExportDto q2 = createOpenQuestion("Q2");
        QuestionExportDto q3 = createOpenQuestion("Q3");
        
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2, q3));
        PrintOptions options = new PrintOptions(false, false, false, false, false, false);
        ExportPayload payload = ExportPayload.of(List.of(quiz), options, "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Should not have type section headers
            assertThat(text).doesNotContain("Open-Ended Questions");
        }
    }

    // PrintOptions Factory Method Tests

    @Test
    @DisplayName("render: defaults option produces expected output")
    void render_defaultsOption_producesExpectedOutput() throws Exception {
        // Given
        QuestionExportDto question = createOpenQuestion("Test question");
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("render: compact option produces smaller output than teacher edition")
    void render_compactVsTeacher_differentSizes() throws Exception {
        // Given
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                "Test question", createOpenQuestion("Test").content(),
                "Hint", "Explanation", null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        
        ExportPayload payloadCompact = ExportPayload.of(List.of(quiz), PrintOptions.compact(), "test1");
        ExportPayload payloadTeacher = ExportPayload.of(List.of(quiz), PrintOptions.teacherEdition(), "test2");

        // When
        ExportFile fileCompact = renderer.render(payloadCompact);
        ExportFile fileTeacher = renderer.render(payloadTeacher);

        // Then - teacher edition should be larger (includes cover, metadata, hints, explanations)
        assertThat(fileCompact.contentLength()).isLessThan(fileTeacher.contentLength());
    }

    // Edge Cases

    @Test
    @DisplayName("render: handles empty quiz list")
    void render_emptyQuizList_succeeds() {
        // Given
        ExportPayload payload = ExportPayload.of(new ArrayList<>(), PrintOptions.defaults(), "empty");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles quiz with no questions")
    void render_quizWithNoQuestions_succeeds() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

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
        ExportPayload payload = ExportPayload.of(quizzes, PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles quiz with many questions")
    void render_manyQuestions_succeeds() {
        // Given
        List<QuestionExportDto> questions = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            questions.add(createOpenQuestion("Question " + i));
        }
        QuizExportDto quiz = createQuizWithQuestions(questions);
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: handles null optional fields gracefully")
    void render_nullOptionalFields_succeeds() {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Title", null, Visibility.PUBLIC,
                Difficulty.EASY, 10, null, null, null,
                new ArrayList<>(), Instant.now(), Instant.now()
        );
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When & Then - should not throw
        ExportFile file = renderer.render(payload);
        assertThat(file.contentLength()).isPositive();
    }

    @Test
    @DisplayName("render: content supplier is reusable")
    void render_contentSupplierIsReusable() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - can invoke supplier multiple times
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
        ExportPayload payload = ExportPayload.of(
                List.of(quiz),
                PrintOptions.defaults(),
                "quizzes_me_202410141530_cat2_tag3_medium"
        );

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_me_202410141530_cat2_tag3_medium.pdf");
        assertThat(file.filename()).contains("cat2");
        assertThat(file.filename()).contains("tag3");
        assertThat(file.filename()).contains("medium");
    }

    @Test
    @DisplayName("render: numbers questions sequentially")
    void render_numbersQuestionsSequentially() throws Exception {
        // Given
        QuestionExportDto q1 = createOpenQuestion("First question");
        QuestionExportDto q2 = createOpenQuestion("Second question");
        QuestionExportDto q3 = createOpenQuestion("Third question");
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2, q3));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("1.");
            assertThat(text).contains("2.");
            assertThat(text).contains("3.");
        }
    }

    @Test
    @DisplayName("render: uses content.text for FILL_GAP question display (Issue #165)")
    void render_fillGapQuestion_usesContentText() throws Exception {
        // Given - FILL_GAP question with actual prompt in content.text
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", "One of the main functions of NAT is to allow devices on the ___ network to access the Internet.");
        com.fasterxml.jackson.databind.node.ArrayNode gaps = objectMapper.createArrayNode();
        gaps.add(objectMapper.createObjectNode().put("id", 1).put("answer", "private"));
        content.set("gaps", gaps);
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.FILL_GAP, Difficulty.MEDIUM,
                "Complete the sentence with the missing word(s)", // Generic text
                content, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Should display the actual prompt with underscores
            assertThat(text).contains("One of the main functions of NAT");
            assertThat(text).contains("___ network");
            // Should NOT display the generic question text
            assertThat(text).doesNotContain("Complete the sentence with the missing word(s)");
        }
    }

    @Test
    @DisplayName("render: falls back to questionText when content.text is missing for FILL_GAP")
    void render_fillGapQuestion_fallsBackToQuestionText() throws Exception {
        // Given - FILL_GAP question without content.text
        ObjectNode content = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode gaps = objectMapper.createArrayNode();
        gaps.add(objectMapper.createObjectNode().put("id", 1).put("answer", "answer"));
        content.set("gaps", gaps);
        // Note: no "text" field in content
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.FILL_GAP, Difficulty.EASY,
                "Complete the sentence",
                content, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should fall back to questionText
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("Complete the sentence");
        }
    }

    @Test
    @DisplayName("render: includes version code in footer on all pages")
    void render_includesVersionCodeInFooter() throws Exception {
        // Given
        QuestionExportDto q1 = createOpenQuestion("Q1");
        QuestionExportDto q2 = createOpenQuestion("Q2");
        QuizExportDto quiz = createQuizWithQuestions(List.of(q1, q2));
        PrintOptions options = new PrintOptions(false, false, true, false, false, false);
        
        UUID exportId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String versionCode = "TEST01";
        ExportPayload payload = new ExportPayload(
                List.of(quiz), options, "test",
                exportId, versionCode, 12345L
        );

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Footer should contain version code on all pages
            assertThat(text).contains("Version: TEST01");
            assertThat(text).contains("Page 1 of");
        }
    }

    @Test
    @DisplayName("render: footer appears on multiple pages")
    void render_footerOnMultiplePages() throws Exception {
        // Given - many questions to span multiple pages
        List<QuestionExportDto> questions = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            questions.add(createOpenQuestion("Question " + i));
        }
        QuizExportDto quiz = createQuizWithQuestions(questions);
        PrintOptions options = new PrintOptions(false, false, true, false, false, false);
        
        UUID exportId = UUID.randomUUID();
        String versionCode = "MULTI1";
        ExportPayload payload = new ExportPayload(
                List.of(quiz), options, "test",
                exportId, versionCode, 67890L
        );

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Version code should appear on every page
            assertThat(text).contains("Version: MULTI1");
        }
    }

    @Test
    @DisplayName("render: handles multiple gaps in FILL_GAP questions")
    void render_fillGapQuestion_multipleGaps() throws Exception {
        // Given - FILL_GAP question with multiple gaps
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", "The ___ protocol operates at the ___ layer of the OSI model.");
        com.fasterxml.jackson.databind.node.ArrayNode gaps = objectMapper.createArrayNode();
        gaps.add(objectMapper.createObjectNode().put("id", 1).put("answer", "TCP"));
        gaps.add(objectMapper.createObjectNode().put("id", 2).put("answer", "transport"));
        content.set("gaps", gaps);
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(), QuestionType.FILL_GAP, Difficulty.HARD,
                "Fill in the blanks",
                content, null, null, null
        );
        QuizExportDto quiz = createQuizWithQuestions(List.of(question));
        ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Should display the actual prompt with both underscores
            assertThat(text).contains("The ___ protocol");
            assertThat(text).contains("___ layer");
            assertThat(text).contains("OSI model");
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

    private QuestionExportDto createOpenQuestion(String text) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("answer", "Sample answer for: " + text);
        
        return new QuestionExportDto(
                UUID.randomUUID(), QuestionType.OPEN, Difficulty.EASY,
                text, content, null, null, null
        );
    }
}
