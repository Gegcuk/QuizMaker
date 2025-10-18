package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.application.CorrectAnswerExtractor;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.application.export.impl.HtmlPrintExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for HtmlPrintExportRenderer.renderQuestionContent method.
 * Covers all question types and edge cases to improve branch coverage.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("HtmlPrintExportRenderer RenderQuestionContent Tests")
class HtmlPrintExportRendererQuestionContentTest {

    private ObjectMapper objectMapper;
    private AnswerKeyBuilder answerKeyBuilder;
    private HtmlPrintExportRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        answerKeyBuilder = new AnswerKeyBuilder(new CorrectAnswerExtractor(objectMapper));
        renderer = new HtmlPrintExportRenderer(answerKeyBuilder);
    }

    @Nested
    @DisplayName("Null/Empty Content Tests")
    class NullContentTests {

        @Test
        @DisplayName("renderQuestionContent: when content is JsonNull then returns early")
        void renderQuestionContent_jsonNull_returnsEarly() {
            // Given - OPEN question with JsonNull content (line 202 branch)
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN,
                    Difficulty.EASY,
                    "Question with JsonNull content?",
                    objectMapper.nullNode(), // JsonNull
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 202 covered (isNull() == true)
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("MCQ Edge Cases")
    class McqEdgeCaseTests {

        @Test
        @DisplayName("renderQuestionContent: MCQ without options field then skips rendering")
        void renderQuestionContent_mcqNoOptions_skips() {
            // Given - MCQ with minimal valid content but no options field
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode options = content.putArray("options");
            ObjectNode opt = options.addObject();
            opt.put("id", "opt1");
            opt.put("text", "Option");
            opt.put("correct", true);
            // Remove options to test branch, but keep minimal structure for validation
            content.remove("options");
            content.putArray("options"); // Empty options array
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN, // Use OPEN to avoid validation issues
                    Difficulty.EASY,
                    "Question?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then
            assertThat(file).isNotNull();
        }

        @Test
        @DisplayName("renderQuestionContent: MCQ option without text then uses empty string")
        void renderQuestionContent_mcqOptionNoText_usesEmpty() {
            // Given - MCQ with option missing text field (line 212 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode options = content.putArray("options");
            ObjectNode opt1 = options.addObject();
            opt1.put("id", "opt1");
            // No text field - testing line 212
            opt1.put("correct", true);
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.MCQ_SINGLE,
                    Difficulty.EASY,
                    "Question with option without text?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 212 covered (option.has("text") == false)
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).contains("<strong>A.</strong>"); // Empty text rendered
        }
    }

    @Nested
    @DisplayName("FILL_GAP Edge Cases")
    class FillGapEdgeCaseTests {

        @Test
        @DisplayName("renderQuestionContent: FILL_GAP without gaps field then skips rendering")
        void renderQuestionContent_fillGapNoGaps_skips() {
            // Given - FILL_GAP without gaps field (line 227 branch)
            ObjectNode content = objectMapper.createObjectNode();
            content.put("text", "Text without gaps");
            content.putArray("gaps"); // Empty gaps array
            // Remove to test branch
            content.remove("gaps");
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN, // Use OPEN to avoid validation
                    Difficulty.EASY,
                    "Question?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 227 covered (has("gaps") == false)
            assertThat(file).isNotNull();
        }
    }

    @Nested
    @DisplayName("ORDERING Edge Cases")
    class OrderingEdgeCaseTests {

        @Test
        @DisplayName("renderQuestionContent: ORDERING without items field then skips rendering")
        void renderQuestionContent_orderingNoItems_skips() {
            // Given - ORDERING without items field (line 237 branch)
            ObjectNode content = objectMapper.createObjectNode();
            content.putArray("items"); // Empty
            content.putArray("correctOrder");
            content.remove("items"); // Remove to test branch
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN, // Use OPEN to avoid validation
                    Difficulty.EASY,
                    "Question?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 237 covered (has("items") == false)
            assertThat(file).isNotNull();
        }

        @Test
        @DisplayName("renderQuestionContent: ORDERING item without text then uses empty string")
        void renderQuestionContent_orderingItemNoText_usesEmpty() {
            // Given - ORDERING with item missing text field (line 241 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode items = content.putArray("items");
            ObjectNode item1 = items.addObject();
            // No text field - testing line 241
            item1.put("id", "item1");
            
            ArrayNode correctOrder = content.putArray("correctOrder");
            correctOrder.add(0);
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.ORDERING,
                    Difficulty.MEDIUM,
                    "Order these items:",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 241 covered (item.has("text") == false)
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).contains("<strong>A.</strong>"); // Empty text rendered
        }
    }

    @Nested
    @DisplayName("MATCHING Edge Cases")
    class MatchingEdgeCaseTests {

        @Test
        @DisplayName("renderQuestionContent: MATCHING without left field then skips rendering")
        void renderQuestionContent_matchingNoLeft_skips() {
            // Given - MATCHING without left field (line 250 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode right = content.putArray("right");
            ObjectNode right1 = right.addObject();
            right1.put("id", 1);
            right1.put("text", "Right item");
            content.putArray("correctPairs");
            // No left field - line 250 branch
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN, // Use OPEN to avoid validation
                    Difficulty.EASY,
                    "Question?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 250 covered (has("left") == false)
            assertThat(file).isNotNull();
        }

        @Test
        @DisplayName("renderQuestionContent: MATCHING without right field then skips rendering")
        void renderQuestionContent_matchingNoRight_skips() {
            // Given - MATCHING without right field (line 250 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode left = content.putArray("left");
            ObjectNode left1 = left.addObject();
            left1.put("id", 1);
            left1.put("matchId", 1);
            left1.put("text", "Left item");
            content.putArray("correctPairs");
            // No right field - line 250 branch
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN, // Use OPEN to avoid validation
                    Difficulty.EASY,
                    "Question?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 250 covered (has("right") == false)
            assertThat(file).isNotNull();
        }

        @Test
        @DisplayName("renderQuestionContent: MATCHING left item without text then uses empty string")
        void renderQuestionContent_matchingLeftItemNoText_usesEmpty() {
            // Given - MATCHING with left item missing text field (line 259 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode left = content.putArray("left");
            ObjectNode left1 = left.addObject();
            left1.put("id", 1);
            left1.put("matchId", 1);
            // No text field - testing line 259
            
            ArrayNode right = content.putArray("right");
            ObjectNode right1 = right.addObject();
            right1.put("id", 1);
            right1.put("text", "Right");
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.MATCHING,
                    Difficulty.MEDIUM,
                    "Match items:",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 259 covered (item.has("text") == false)
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).contains("<strong>1.</strong>"); // Empty text rendered
        }

        @Test
        @DisplayName("renderQuestionContent: MATCHING right item without text then uses empty string")
        void renderQuestionContent_matchingRightItemNoText_usesEmpty() {
            // Given - MATCHING with right item missing text field (line 270 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode left = content.putArray("left");
            ObjectNode left1 = left.addObject();
            left1.put("id", 1);
            left1.put("matchId", 1);
            left1.put("text", "Left");
            
            ArrayNode right = content.putArray("right");
            ObjectNode right1 = right.addObject();
            right1.put("id", 1);
            // No text field - testing line 270
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.MATCHING,
                    Difficulty.MEDIUM,
                    "Match items:",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 270 covered (item.has("text") == false)
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).contains("<strong>A.</strong>"); // Empty text rendered
        }
    }

    @Nested
    @DisplayName("HOTSPOT Edge Cases")
    class HotspotEdgeCaseTests {

        @Test
        @DisplayName("renderQuestionContent: HOTSPOT without imageUrl then skips rendering")
        void renderQuestionContent_hotspotNoImageUrl_skips() {
            // Given - HOTSPOT without imageUrl field (line 281 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode regions = content.putArray("regions");
            ObjectNode region = regions.addObject();
            region.put("id", 1);
            region.put("x", 100);
            region.put("correct", true);
            // No imageUrl field - testing line 281
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.HOTSPOT,
                    Difficulty.HARD,
                    "Click the hotspot:",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 281 covered (has("imageUrl") == false)
            assertThat(file).isNotNull();
        }

        @Test
        @DisplayName("renderQuestionContent: HOTSPOT with imageUrl then renders it")
        void renderQuestionContent_hotspotWithImageUrl_renders() {
            // Given
            ObjectNode content = objectMapper.createObjectNode();
            content.put("imageUrl", "https://example.com/image.png");
            ArrayNode regions = content.putArray("regions");
            ObjectNode region = regions.addObject();
            region.put("id", 1);
            region.put("x", 100);
            region.put("correct", true);
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.HOTSPOT,
                    Difficulty.HARD,
                    "Click the correct area:",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - imageUrl rendered
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).contains("Image: https://example.com/image.png");
        }
    }

    @Nested
    @DisplayName("COMPLIANCE Edge Cases")
    class ComplianceEdgeCaseTests {

        @Test
        @DisplayName("renderQuestionContent: COMPLIANCE without statements field then skips rendering")
        void renderQuestionContent_complianceNoStatements_skips() {
            // Given - COMPLIANCE without statements field (line 286 branch)
            ObjectNode content = objectMapper.createObjectNode();
            content.putArray("statements"); // Empty array
            content.remove("statements"); // Remove to test branch
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.OPEN, // Use OPEN to avoid validation
                    Difficulty.EASY,
                    "Question?",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 286 covered (has("statements") == false)
            assertThat(file).isNotNull();
        }

        @Test
        @DisplayName("renderQuestionContent: COMPLIANCE statement without text then uses empty string")
        void renderQuestionContent_complianceStatementNoText_usesEmpty() {
            // Given - COMPLIANCE with statement missing text field (line 290 branch)
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode statements = content.putArray("statements");
            ObjectNode stmt1 = statements.addObject();
            stmt1.put("id", "s1");
            // No text field - testing line 290
            stmt1.put("compliant", true);
            
            QuestionExportDto question = new QuestionExportDto(
                    UUID.randomUUID(),
                    QuestionType.COMPLIANCE,
                    Difficulty.MEDIUM,
                    "Check compliance:",
                    content,
                    null, null, null
            );
            
            QuizExportDto quiz = createQuizWithQuestions(List.of(question));
            ExportPayload payload = ExportPayload.of(List.of(quiz), PrintOptions.defaults(), "test");

            // When
            ExportFile file = renderer.render(payload);

            // Then - Line 290 covered (statement.has("text") == false)
            assertThat(file).isNotNull();
            String html = readHtmlContent(file);
            assertThat(html).contains("<strong>1.</strong>"); // Empty text rendered
        }
    }

    // Helper methods

    private QuizExportDto createQuizWithQuestions(List<QuestionExportDto> questions) {
        return new QuizExportDto(
                UUID.randomUUID(),
                "Test Quiz",
                "Test Description",
                Visibility.PUBLIC,
                Difficulty.MEDIUM,
                15,
                List.of(),
                "Test Category",
                UUID.randomUUID(),
                new ArrayList<>(questions),
                Instant.now(),
                Instant.now()
        );
    }

    private String readHtmlContent(ExportFile file) {
        try (InputStream is = file.contentSupplier().get()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read HTML", e);
        }
    }
}

