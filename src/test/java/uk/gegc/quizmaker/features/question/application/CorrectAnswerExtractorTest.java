package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("CorrectAnswerExtractor Unit Tests")
class CorrectAnswerExtractorTest {

    private CorrectAnswerExtractor extractor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        extractor = new CorrectAnswerExtractor(objectMapper);
    }

    @Test
    @DisplayName("extractCorrectAnswer: MCQ_SINGLE returns correctOptionId")
    void extractMcqSingle_returnsCorrectOptionId() {
        // Given
        String content = """
                {
                    "options": [
                        {"id": "opt_1", "text": "Paris", "correct": true},
                        {"id": "opt_2", "text": "London", "correct": false},
                        {"id": "opt_3", "text": "Berlin", "correct": false}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.MCQ_SINGLE, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.get("correctOptionId").asText()).isEqualTo("opt_1");
    }

    @Test
    @DisplayName("extractCorrectAnswer: MCQ_SINGLE throws when no correct option")
    void extractMcqSingle_throwsWhenNoCorrectOption() {
        // Given
        String content = """
                {
                    "options": [
                        {"id": "opt_1", "text": "Paris", "correct": false},
                        {"id": "opt_2", "text": "London", "correct": false}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.MCQ_SINGLE, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no correct option marked");
    }

    @Test
    @DisplayName("extractCorrectAnswer: MCQ_SINGLE throws when options missing")
    void extractMcqSingle_throwsWhenOptionsMissing() {
        // Given
        String content = "{\"wrongField\": \"value\"}";
        Question question = createQuestion(QuestionType.MCQ_SINGLE, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'options' field");
    }

    @Test
    @DisplayName("extractCorrectAnswer: MCQ_MULTI returns correctOptionIds array")
    void extractMcqMulti_returnsCorrectOptionIds() {
        // Given
        String content = """
                {
                    "options": [
                        {"id": "opt_1", "text": "Java", "correct": true},
                        {"id": "opt_2", "text": "Python", "correct": true},
                        {"id": "opt_3", "text": "PHP", "correct": false},
                        {"id": "opt_4", "text": "JavaScript", "correct": true}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.MCQ_MULTI, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.has("correctOptionIds")).isTrue();
        JsonNode correctIds = result.get("correctOptionIds");
        assertThat(correctIds.isArray()).isTrue();
        assertThat(correctIds.size()).isEqualTo(3);
        assertThat(correctIds.get(0).asText()).isEqualTo("opt_1");
        assertThat(correctIds.get(1).asText()).isEqualTo("opt_2");
        assertThat(correctIds.get(2).asText()).isEqualTo("opt_4");
    }

    @Test
    @DisplayName("extractCorrectAnswer: MCQ_MULTI throws when no correct options")
    void extractMcqMulti_throwsWhenNoCorrectOptions() {
        // Given
        String content = """
                {
                    "options": [
                        {"id": "opt_1", "text": "Java", "correct": false},
                        {"id": "opt_2", "text": "Python", "correct": false}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.MCQ_MULTI, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no correct options marked");
    }

    @Test
    @DisplayName("extractCorrectAnswer: TRUE_FALSE returns boolean answer")
    void extractTrueFalse_returnsAnswer() {
        // Given
        String content = "{\"answer\": true}";
        Question question = createQuestion(QuestionType.TRUE_FALSE, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.get("answer").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("extractCorrectAnswer: TRUE_FALSE throws when answer missing")
    void extractTrueFalse_throwsWhenAnswerMissing() {
        // Given
        String content = "{\"wrongField\": \"value\"}";
        Question question = createQuestion(QuestionType.TRUE_FALSE, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'answer' field");
    }

    @Test
    @DisplayName("extractCorrectAnswer: OPEN returns answer or null")
    void extractOpen_returnsAnswerOrNull() {
        // Given - with answer
        String contentWithAnswer = "{\"answer\": \"Expected canonical text\"}";
        Question question1 = createQuestion(QuestionType.OPEN, contentWithAnswer);

        // When
        JsonNode result1 = extractor.extractCorrectAnswer(question1);

        // Then
        assertThat(result1.get("answer").asText()).isEqualTo("Expected canonical text");

        // Given - without answer (manual grading)
        String contentWithoutAnswer = "{}";
        Question question2 = createQuestion(QuestionType.OPEN, contentWithoutAnswer);

        // When
        JsonNode result2 = extractor.extractCorrectAnswer(question2);

        // Then
        assertThat(result2.get("answer").isNull()).isTrue();
    }

    @Test
    @DisplayName("extractCorrectAnswer: FILL_GAP returns answers array")
    void extractFillGap_returnsAnswers() {
        // Given
        String content = """
                {
                    "text": "The capital of France is {1} and the famous tower is {2}.",
                    "gaps": [
                        {"id": 1, "answer": "Paris"},
                        {"id": 2, "answer": "Eiffel Tower"}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.FILL_GAP, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.has("answers")).isTrue();
        JsonNode answers = result.get("answers");
        assertThat(answers.isArray()).isTrue();
        assertThat(answers.size()).isEqualTo(2);
        assertThat(answers.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(answers.get(0).get("text").asText()).isEqualTo("Paris");
        assertThat(answers.get(1).get("id").asInt()).isEqualTo(2);
        assertThat(answers.get(1).get("text").asText()).isEqualTo("Eiffel Tower");
    }

    @Test
    @DisplayName("extractCorrectAnswer: FILL_GAP throws when gaps missing")
    void extractFillGap_throwsWhenGapsMissing() {
        // Given
        String content = "{\"text\": \"Some text\"}";
        Question question = createQuestion(QuestionType.FILL_GAP, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'gaps' field");
    }

    @Test
    @DisplayName("extractCorrectAnswer: ORDERING returns order array")
    void extractOrdering_returnsOrder() {
        // Given
        String content = """
                {
                    "items": [
                        {"id": 1, "text": "First"},
                        {"id": 2, "text": "Second"},
                        {"id": 3, "text": "Third"},
                        {"id": 4, "text": "Fourth"}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.ORDERING, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.has("order")).isTrue();
        JsonNode order = result.get("order");
        assertThat(order.isArray()).isTrue();
        assertThat(order.size()).isEqualTo(4);
        assertThat(order.get(0).asInt()).isEqualTo(1);
        assertThat(order.get(1).asInt()).isEqualTo(2);
        assertThat(order.get(2).asInt()).isEqualTo(3);
        assertThat(order.get(3).asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("extractCorrectAnswer: ORDERING throws when items missing")
    void extractOrdering_throwsWhenItemsMissing() {
        // Given
        String content = "{}";
        Question question = createQuestion(QuestionType.ORDERING, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'items' field");
    }

    @Test
    @DisplayName("extractCorrectAnswer: MATCHING returns pairs array")
    void extractMatching_returnsPairs() {
        // Given
        String content = """
                {
                    "left": [
                        {"id": 1, "text": "Java", "matchId": 2},
                        {"id": 2, "text": "Python", "matchId": 1}
                    ],
                    "right": [
                        {"id": 1, "text": "Interpreted"},
                        {"id": 2, "text": "Compiled"}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.MATCHING, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.has("pairs")).isTrue();
        JsonNode pairs = result.get("pairs");
        assertThat(pairs.isArray()).isTrue();
        assertThat(pairs.size()).isEqualTo(2);
        assertThat(pairs.get(0).get("leftId").asInt()).isEqualTo(1);
        assertThat(pairs.get(0).get("rightId").asInt()).isEqualTo(2);
        assertThat(pairs.get(1).get("leftId").asInt()).isEqualTo(2);
        assertThat(pairs.get(1).get("rightId").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("extractCorrectAnswer: MATCHING throws when left missing")
    void extractMatching_throwsWhenLeftMissing() {
        // Given
        String content = "{\"right\": []}";
        Question question = createQuestion(QuestionType.MATCHING, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'left' field");
    }

    @Test
    @DisplayName("extractCorrectAnswer: HOTSPOT returns regionId")
    void extractHotspot_returnsRegionId() {
        // Given
        String content = """
                {
                    "imageUrl": "http://example.com/image.png",
                    "regions": [
                        {"id": 1, "x": 0, "y": 0, "width": 100, "height": 100, "correct": false},
                        {"id": 2, "x": 100, "y": 100, "width": 100, "height": 100, "correct": true},
                        {"id": 3, "x": 200, "y": 200, "width": 100, "height": 100, "correct": false}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.HOTSPOT, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.get("regionId").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("extractCorrectAnswer: HOTSPOT throws when no correct region")
    void extractHotspot_throwsWhenNoCorrectRegion() {
        // Given
        String content = """
                {
                    "imageUrl": "http://example.com/image.png",
                    "regions": [
                        {"id": 1, "x": 0, "y": 0, "width": 100, "height": 100, "correct": false},
                        {"id": 2, "x": 100, "y": 100, "width": 100, "height": 100, "correct": false}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.HOTSPOT, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no correct region marked");
    }

    @Test
    @DisplayName("extractCorrectAnswer: COMPLIANCE returns compliantIds array")
    void extractCompliance_returnsCompliantIds() {
        // Given
        String content = """
                {
                    "statements": [
                        {"id": 1, "text": "Statement 1", "compliant": false},
                        {"id": 2, "text": "Statement 2", "compliant": true},
                        {"id": 3, "text": "Statement 3", "compliant": false},
                        {"id": 4, "text": "Statement 4", "compliant": true}
                    ]
                }
                """;
        Question question = createQuestion(QuestionType.COMPLIANCE, content);

        // When
        JsonNode result = extractor.extractCorrectAnswer(question);

        // Then
        assertThat(result.has("compliantIds")).isTrue();
        JsonNode compliantIds = result.get("compliantIds");
        assertThat(compliantIds.isArray()).isTrue();
        assertThat(compliantIds.size()).isEqualTo(2);
        assertThat(compliantIds.get(0).asInt()).isEqualTo(2);
        assertThat(compliantIds.get(1).asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("extractCorrectAnswer: COMPLIANCE throws when statements missing")
    void extractCompliance_throwsWhenStatementsMissing() {
        // Given
        String content = "{}";
        Question question = createQuestion(QuestionType.COMPLIANCE, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'statements' field");
    }

    @Test
    @DisplayName("extractCorrectAnswer: throws when JSON is malformed")
    void extractCorrectAnswer_throwsWhenMalformedJson() {
        // Given
        String content = "{invalid json";
        Question question = createQuestion(QuestionType.MCQ_SINGLE, content);

        // When & Then
        assertThatThrownBy(() -> extractor.extractCorrectAnswer(question))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse question content");
    }

    // Helper method to create a Question with given type and content
    private Question createQuestion(QuestionType type, String content) {
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(type);
        question.setContent(content);
        question.setQuestionText("Test question");
        return question;
    }
}
