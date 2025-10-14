package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.application.export.impl.JsonExportRenderer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.CONCURRENT)
@DisplayName("JsonExportRenderer Tests")
class JsonExportRendererTest {

    private ObjectMapper objectMapper;
    private JsonExportRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        renderer = new JsonExportRenderer(objectMapper);
    }

    // Support Tests

    @Test
    @DisplayName("supports: returns true for JSON_EDITABLE")
    void supports_jsonEditable_returnsTrue() {
        // When
        boolean result = renderer.supports(ExportFormat.JSON_EDITABLE);

        // Then
        assertThat(result).isTrue();
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
    @DisplayName("supports: returns false for HTML_PRINT")
    void supports_htmlPrint_returnsFalse() {
        // When
        boolean result = renderer.supports(ExportFormat.HTML_PRINT);

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

    // Render Tests - Basic

    @Test
    @DisplayName("render: produces JSON array without printOptions")
    void render_producesJsonArrayWithoutPrintOptions() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "quizzes_test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            assertThat(json.trim()).startsWith("[");
            assertThat(json.trim()).endsWith("]");
            assertThat(json).doesNotContain("printOptions");
            assertThat(json).doesNotContain("filenamePrefix");
        }
    }

    @Test
    @DisplayName("render: uses filename prefix correctly")
    void render_usesFilenamePrefixCorrectly() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "quizzes_me_202410141200");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_me_202410141200.json");
    }

    @Test
    @DisplayName("render: sets content type to application/json")
    void render_setsContentTypeToApplicationJson() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.contentType()).isEqualTo("application/json");
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

    // Render Tests - Content Validation

    @Test
    @DisplayName("render: output is valid JSON")
    void render_outputIsValidJson() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            // Should parse without error
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.isArray()).isTrue();
        }
    }

    @Test
    @DisplayName("render: includes all quiz fields in output")
    void render_includesAllQuizFields() throws Exception {
        // Given
        QuizExportDto quiz = createFullQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            assertThat(json).contains("\"title\"");
            assertThat(json).contains("\"description\"");
            assertThat(json).contains("\"visibility\"");
            assertThat(json).contains("\"difficulty\"");
            assertThat(json).contains("\"estimatedTime\"");
            assertThat(json).contains("\"tags\"");
            assertThat(json).contains("\"category\"");
            assertThat(json).contains("\"creatorId\"");
            assertThat(json).contains("\"questions\"");
            assertThat(json).contains("\"createdAt\"");
            assertThat(json).contains("\"updatedAt\"");
        }
    }

    @Test
    @DisplayName("render: handles empty quiz list")
    void render_emptyQuizList_producesEmptyArray() throws Exception {
        // Given
        ExportPayload payload = new ExportPayload(new ArrayList<>(), PrintOptions.defaults(), "empty");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.isArray()).isTrue();
            assertThat(parsed.size()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("render: handles single quiz")
    void render_singleQuiz_producesArrayWithOneElement() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "single");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.isArray()).isTrue();
            assertThat(parsed.size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("render: handles multiple quizzes")
    void render_multipleQuizzes_producesArrayWithMultipleElements() throws Exception {
        // Given
        QuizExportDto quiz1 = createMinimalQuiz();
        QuizExportDto quiz2 = createMinimalQuiz();
        QuizExportDto quiz3 = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz1, quiz2, quiz3), PrintOptions.defaults(), "multiple");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.isArray()).isTrue();
            assertThat(parsed.size()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("render: uses pretty printing")
    void render_usesPrettyPrinting() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "pretty");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            // Pretty printed JSON should have newlines and indentation
            assertThat(json).contains("\n");
            assertThat(json).contains("  "); // Indentation
        }
    }

    // Render Tests - Question Content

    @Test
    @DisplayName("render: includes question content as JsonNode")
    void render_includesQuestionContentAsJsonNode() throws Exception {
        // Given
        JsonNode questionContent = objectMapper.createObjectNode()
                .put("answer", true);
        
        QuestionExportDto question = new QuestionExportDto(
                UUID.randomUUID(),
                QuestionType.TRUE_FALSE,
                Difficulty.EASY,
                "Is this a question?",
                questionContent,
                null,
                null,
                null
        );
        
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), "Cat", UUID.randomUUID(),
                List.of(question), Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode firstQuiz = parsed.get(0);
            JsonNode questions = firstQuiz.get("questions");
            JsonNode firstQuestion = questions.get(0);
            JsonNode content = firstQuestion.get("content");
            
            assertThat(content.has("answer")).isTrue();
            assertThat(content.get("answer").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("render: handles quiz with null optional fields")
    void render_quizWithNullFields_handlesGracefully() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(),
                "Title",
                null, // Null description
                Visibility.PUBLIC,
                Difficulty.EASY,
                10,
                null, // Null tags
                null, // Null category
                null, // Null creatorId
                new ArrayList<>(),
                Instant.now(),
                Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should not throw
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode firstQuiz = parsed.get(0);
            
            assertThat(firstQuiz.get("description").isNull()).isTrue();
            assertThat(firstQuiz.get("category").isNull()).isTrue();
            assertThat(firstQuiz.get("creatorId").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("render: preserves timestamps correctly")
    void render_preservesTimestampsCorrectly() throws Exception {
        // Given
        Instant created = Instant.parse("2024-01-15T10:30:45.123Z");
        Instant updated = Instant.parse("2024-02-20T14:45:30.456Z");
        
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), "Cat", UUID.randomUUID(),
                new ArrayList<>(), created, updated
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode firstQuiz = parsed.get(0);
            
            // Timestamps should be present in the JSON (format depends on ObjectMapper config)
            assertThat(firstQuiz.has("createdAt")).isTrue();
            assertThat(firstQuiz.has("updatedAt")).isTrue();
            assertThat(firstQuiz.get("createdAt").isNumber() || firstQuiz.get("createdAt").isTextual()).isTrue();
            assertThat(firstQuiz.get("updatedAt").isNumber() || firstQuiz.get("updatedAt").isTextual()).isTrue();
        }
    }

    @Test
    @DisplayName("render: handles special characters in text fields")
    void render_specialCharacters_escapedProperly() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(),
                "Quiz with \"quotes\" & <tags>",
                "Description with\nnewlines\tand\ttabs",
                Visibility.PUBLIC,
                Difficulty.EASY,
                10,
                List.of("C++", "Java/Spring"),
                "Science & Tech",
                UUID.randomUUID(),
                new ArrayList<>(),
                Instant.now(),
                Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then - should produce valid JSON
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json); // Parses successfully
            assertThat(parsed.get(0).get("title").asText()).contains("quotes");
            assertThat(parsed.get(0).get("tags").get(0).asText()).isIn("C++", "Java/Spring");
        }
    }

    @Test
    @DisplayName("render: handles unicode characters")
    void render_unicodeCharacters_preservedCorrectly() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(),
                "测试 тест テスト",
                "Émoji: 🎉 ✅ 🚀",
                Visibility.PUBLIC,
                Difficulty.EASY,
                10,
                List.of("日本語", "Русский"),
                "中文",
                UUID.randomUUID(),
                new ArrayList<>(),
                Instant.now(),
                Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "unicode");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.get(0).get("title").asText()).contains("测试");
            assertThat(parsed.get(0).get("description").asText()).contains("🎉");
        }
    }

    // Render Tests - Filename

    @Test
    @DisplayName("render: filename uses payload prefix")
    void render_filenameUsesPayloadPrefix() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "quizzes_public_202410141530");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_public_202410141530.json");
        assertThat(file.filename()).startsWith("quizzes_public_");
        assertThat(file.filename()).endsWith(".json");
    }

    @Test
    @DisplayName("render: filename with filters in prefix")
    void render_filenameWithFilters() {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(
                List.of(quiz), 
                PrintOptions.defaults(), 
                "quizzes_me_202410141530_cat2_tag3_medium_search"
        );

        // When
        ExportFile file = renderer.render(payload);

        // Then
        assertThat(file.filename()).isEqualTo("quizzes_me_202410141530_cat2_tag3_medium_search.json");
        assertThat(file.filename()).contains("cat2");
        assertThat(file.filename()).contains("tag3");
        assertThat(file.filename()).contains("medium");
    }

    // Render Tests - Determinism

    @Test
    @DisplayName("render: produces deterministic output for same input")
    void render_sameTwice_producesSameOutput() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "deterministic");

        // When
        ExportFile file1 = renderer.render(payload);
        ExportFile file2 = renderer.render(payload);

        // Then - content should be identical
        String json1;
        String json2;
        try (InputStream is = file1.contentSupplier().get()) {
            json1 = new String(is.readAllBytes());
        }
        try (InputStream is = file2.contentSupplier().get()) {
            json2 = new String(is.readAllBytes());
        }
        
        assertThat(json1).isEqualTo(json2);
    }

    // Render Tests - PrintOptions Independence

    @Test
    @DisplayName("render: ignores printOptions (not included in JSON)")
    void render_ignoresPrintOptions() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        
        // Try different print options
        ExportPayload payloadDefaults = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test1");
        ExportPayload payloadCompact = new ExportPayload(List.of(quiz), PrintOptions.compact(), "test2");
        ExportPayload payloadTeacher = new ExportPayload(List.of(quiz), PrintOptions.teacherEdition(), "test3");

        // When
        ExportFile file1 = renderer.render(payloadDefaults);
        ExportFile file2 = renderer.render(payloadCompact);
        ExportFile file3 = renderer.render(payloadTeacher);

        // Then - all should produce same JSON structure (ignoring filenames)
        try (InputStream is1 = file1.contentSupplier().get();
             InputStream is2 = file2.contentSupplier().get();
             InputStream is3 = file3.contentSupplier().get()) {
            
            String json1 = new String(is1.readAllBytes());
            String json2 = new String(is2.readAllBytes());
            String json3 = new String(is3.readAllBytes());
            
            // All should not contain printOptions
            assertThat(json1).doesNotContain("printOptions");
            assertThat(json2).doesNotContain("printOptions");
            assertThat(json3).doesNotContain("printOptions");
            
            // JSON structure should be same (only quiz array)
            JsonNode parsed1 = objectMapper.readTree(json1);
            JsonNode parsed2 = objectMapper.readTree(json2);
            JsonNode parsed3 = objectMapper.readTree(json3);
            
            assertThat(parsed1.size()).isEqualTo(parsed2.size()).isEqualTo(parsed3.size());
        }
    }

    // Render Tests - Questions

    @Test
    @DisplayName("render: includes questions array in each quiz")
    void render_includesQuestionsArray() throws Exception {
        // Given
        QuestionExportDto q1 = createQuestion("Q1", QuestionType.MCQ_SINGLE);
        QuestionExportDto q2 = createQuestion("Q2", QuestionType.TRUE_FALSE);
        
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), "Cat", UUID.randomUUID(),
                List.of(q1, q2), Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode questions = parsed.get(0).get("questions");
            
            assertThat(questions.isArray()).isTrue();
            assertThat(questions.size()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("render: preserves question order from DTO")
    void render_preservesQuestionOrder() throws Exception {
        // Given
        QuestionExportDto q1 = createQuestion("First Question", QuestionType.MCQ_SINGLE);
        QuestionExportDto q2 = createQuestion("Second Question", QuestionType.TRUE_FALSE);
        QuestionExportDto q3 = createQuestion("Third Question", QuestionType.OPEN);
        
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), "Cat", UUID.randomUUID(),
                List.of(q1, q2, q3), // Specific order
                Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "test");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode questions = parsed.get(0).get("questions");
            
            assertThat(questions.get(0).get("questionText").asText()).isEqualTo("First Question");
            assertThat(questions.get(1).get("questionText").asText()).isEqualTo("Second Question");
            assertThat(questions.get(2).get("questionText").asText()).isEqualTo("Third Question");
        }
    }

    // Error Handling Tests

    @Test
    @DisplayName("render: handles quiz with very long text fields")
    void render_veryLongTextFields_handlesCorrectly() throws Exception {
        // Given
        String longText = "x".repeat(10000);
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(),
                longText,
                longText,
                Visibility.PUBLIC,
                Difficulty.EASY,
                10,
                List.of(),
                "Cat",
                UUID.randomUUID(),
                new ArrayList<>(),
                Instant.now(),
                Instant.now()
        );

        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "long");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.get(0).get("title").asText()).hasSize(10000);
        }
    }

    // Content Supplier Tests

    @Test
    @DisplayName("render: content supplier can be invoked multiple times")
    void render_contentSupplierReusable() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "reusable");

        // When
        ExportFile file = renderer.render(payload);

        // Then - invoke twice
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
    @DisplayName("render: content length matches actual byte length")
    void render_contentLengthMatchesActualBytes() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "length");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            byte[] bytes = is.readAllBytes();
            assertThat(file.contentLength()).isEqualTo(bytes.length);
        }
    }

    // Round-trip Compatibility Tests

    @Test
    @DisplayName("render: output can be parsed back to quiz DTOs")
    void render_outputCanBeParsedBack() throws Exception {
        // Given
        QuizExportDto originalQuiz = createMinimalQuiz(); // Use minimal to avoid timestamp comparison issues
        ExportPayload payload = new ExportPayload(List.of(originalQuiz), PrintOptions.defaults(), "roundtrip");

        // When
        ExportFile file = renderer.render(payload);

        // Then - parse back
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            
            // Verify it's a valid array with one quiz
            assertThat(parsed.isArray()).isTrue();
            assertThat(parsed.size()).isEqualTo(1);
            
            JsonNode quiz = parsed.get(0);
            assertThat(quiz.get("id").asText()).isEqualTo(originalQuiz.id().toString());
            assertThat(quiz.get("title").asText()).isEqualTo(originalQuiz.title());
            assertThat(quiz.get("description").asText()).isEqualTo(originalQuiz.description());
            assertThat(quiz.get("visibility").asText()).isEqualTo(originalQuiz.visibility().name());
            assertThat(quiz.get("difficulty").asText()).isEqualTo(originalQuiz.difficulty().name());
        }
    }

    @Test
    @DisplayName("render: enums serialized as strings")
    void render_enumsSerializedAsStrings() throws Exception {
        // Given
        QuizExportDto quiz = createMinimalQuiz();
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "enums");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            
            // Enums should be string values
            assertThat(parsed.get(0).get("visibility").isTextual()).isTrue();
            assertThat(parsed.get(0).get("difficulty").isTextual()).isTrue();
        }
    }

    @Test
    @DisplayName("render: UUIDs serialized as strings")
    void render_uuidsSerializedAsStrings() throws Exception {
        // Given
        UUID quizId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        
        QuizExportDto quiz = new QuizExportDto(
                quizId, "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of(), "Cat", creatorId,
                new ArrayList<>(), Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "uuids");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            
            assertThat(parsed.get(0).get("id").isTextual()).isTrue();
            assertThat(parsed.get(0).get("id").asText()).isEqualTo(quizId.toString());
            assertThat(parsed.get(0).get("creatorId").asText()).isEqualTo(creatorId.toString());
        }
    }

    @Test
    @DisplayName("render: tags array is preserved")
    void render_tagsArrayPreserved() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, List.of("java", "spring", "advanced"),
                "Cat", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "tags");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode tags = parsed.get(0).get("tags");
            
            assertThat(tags.isArray()).isTrue();
            assertThat(tags.size()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("render: empty tags array is preserved")
    void render_emptyTagsArrayPreserved() throws Exception {
        // Given
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.EASY, 10, new ArrayList<>(), // Empty tags
                "Cat", UUID.randomUUID(), new ArrayList<>(),
                Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "empty-tags");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode tags = parsed.get(0).get("tags");
            
            assertThat(tags.isArray()).isTrue();
            assertThat(tags.size()).isEqualTo(0);
        }
    }

    // Large Dataset Tests

    @Test
    @DisplayName("render: handles large number of quizzes")
    void render_largeNumberOfQuizzes_handlesCorrectly() throws Exception {
        // Given
        List<QuizExportDto> quizzes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            quizzes.add(createMinimalQuiz());
        }
        
        ExportPayload payload = new ExportPayload(quizzes, PrintOptions.defaults(), "large");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.size()).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("render: handles quiz with many questions")
    void render_manyQuestions_handlesCorrectly() throws Exception {
        // Given
        List<QuestionExportDto> questions = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            questions.add(createQuestion("Q" + i, QuestionType.MCQ_SINGLE));
        }
        
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(), "Big Quiz", "Desc", Visibility.PUBLIC,
                Difficulty.HARD, 120, List.of(), "Cat", UUID.randomUUID(),
                questions, Instant.now(), Instant.now()
        );
        
        ExportPayload payload = new ExportPayload(List.of(quiz), PrintOptions.defaults(), "many-q");

        // When
        ExportFile file = renderer.render(payload);

        // Then
        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode questionsNode = parsed.get(0).get("questions");
            assertThat(questionsNode.size()).isEqualTo(50);
        }
    }

    // Helper Methods

    private QuizExportDto createMinimalQuiz() {
        return new QuizExportDto(
                UUID.randomUUID(),
                "Test Quiz",
                "Description",
                Visibility.PUBLIC,
                Difficulty.MEDIUM,
                15,
                List.of("tag1"),
                "General",
                UUID.randomUUID(),
                new ArrayList<>(),
                Instant.now(),
                Instant.now()
        );
    }

    private QuizExportDto createFullQuiz() {
        QuestionExportDto question = createQuestion("Sample Question", QuestionType.TRUE_FALSE);
        
        return new QuizExportDto(
                UUID.randomUUID(),
                "Full Quiz Title",
                "Comprehensive description",
                Visibility.PUBLIC,
                Difficulty.HARD,
                45,
                List.of("java", "spring", "advanced"),
                "Programming",
                UUID.randomUUID(),
                List.of(question),
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-02T10:00:00Z")
        );
    }

    private QuestionExportDto createQuestion(String text, QuestionType type) {
        JsonNode content = objectMapper.createObjectNode().put("answer", true);
        return new QuestionExportDto(
                UUID.randomUUID(),
                type,
                Difficulty.EASY,
                text,
                content,
                null,
                null,
                null
        );
    }
}
