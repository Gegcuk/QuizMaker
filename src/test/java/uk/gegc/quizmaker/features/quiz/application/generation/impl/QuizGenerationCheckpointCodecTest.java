package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GeneratedQuizCheckpoint;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Quiz generation checkpoint codec")
class QuizGenerationCheckpointCodecTest {

    private QuizGenerationCheckpointCodec codec;

    @BeforeEach
    void setUp() {
        codec = new QuizGenerationCheckpointCodec(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("Round trip preserves chunk order and every persisted question scalar")
    void roundTripPreservesQuestionScalarsAndChunkOrder() {
        UUID attachmentAssetId = UUID.randomUUID();
        Question first = question("First", QuestionType.MCQ_SINGLE, "{\"correctOptionId\":\"a\"}");
        first.setHint("Hint");
        first.setExplanation("Explanation");
        first.setAttachmentUrl("https://cdn.example.test/question.png");
        first.setAttachmentAssetId(attachmentAssetId);
        Question second = question("Second", QuestionType.TRUE_FALSE, "{\"correct\":true}");
        Map<Integer, List<Question>> chunks = new LinkedHashMap<>();
        chunks.put(4, List.of(second));
        chunks.put(1, List.of(first));

        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(chunks, 1_000_000);
        GeneratedQuizCheckpoint decoded = codec.decode(
                encoded.schemaVersion(), encoded.payload(), encoded.questionCount(), 1_000_000);

        assertThat(decoded.schemaVersion()).isEqualTo(QuizGenerationCheckpointCodec.CURRENT_SCHEMA_VERSION);
        assertThat(decoded.questionCount()).isEqualTo(2);
        assertThat(decoded.chunkQuestions().keySet()).containsExactly(1, 4);
        Question restored = decoded.chunkQuestions().get(1).get(0);
        assertThat(restored.getId()).isNull();
        assertThat(restored.getQuizId()).isEmpty();
        assertThat(restored.getTags()).isEmpty();
        assertThat(restored.getType()).isEqualTo(QuestionType.MCQ_SINGLE);
        assertThat(restored.getDifficulty()).isEqualTo(Difficulty.HARD);
        assertThat(restored.getQuestionText()).isEqualTo("First");
        assertThat(restored.getContent()).isEqualTo("{\"correctOptionId\":\"a\"}");
        assertThat(restored.getHint()).isEqualTo("Hint");
        assertThat(restored.getExplanation()).isEqualTo("Explanation");
        assertThat(restored.getAttachmentUrl()).isEqualTo("https://cdn.example.test/question.png");
        assertThat(restored.getAttachmentAssetId()).isEqualTo(attachmentAssetId);
    }

    @Test
    @DisplayName("Encoding rejects invalid generated question content before persistence")
    void encodeRejectsInvalidQuestionContent() {
        Question invalid = question("Invalid", QuestionType.MCQ_SINGLE, "not-json");

        assertThatThrownBy(() -> codec.encode(Map.of(0, List.of(invalid)), 1_000_000))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    @DisplayName("Encoding rejects a null chunk index with a checkpoint validation error")
    void encodeRejectsNullChunkIndex() {
        Map<Integer, List<Question>> chunks = new LinkedHashMap<>();
        chunks.put(null, List.of(question("Question", QuestionType.OPEN, "{}")));

        assertThatThrownBy(() -> codec.encode(chunks, 1_000_000))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("chunk index");
    }

    @Test
    @DisplayName("Encoding rejects payloads above the configured UTF-8 byte bound")
    void encodeRejectsOversizedPayload() {
        Question question = question("Large", QuestionType.OPEN, "{\"answer\":\"" + "x".repeat(200) + "\"}");

        assertThatThrownBy(() -> codec.encode(Map.of(0, List.of(question)), 32))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    @DisplayName("Decoding rejects an unsupported schema version")
    void decodeRejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> codec.decode(2, "{}", 1, 1_000))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    @DisplayName("Decoding rejects malformed checkpoint JSON")
    void decodeRejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode(1, "not-json", 1, 1_000))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    @DisplayName("Decoding rejects an incomplete question snapshot")
    void decodeRejectsIncompleteQuestionSnapshot() {
        String payload = """
                {
                  "schemaVersion": 1,
                  "chunks": [{
                    "chunkIndex": 0,
                    "questions": [{
                      "type": "MCQ_SINGLE",
                      "difficulty": "HARD",
                      "content": "{}"
                    }]
                  }]
                }
                """;

        assertThatThrownBy(() -> codec.decode(1, payload, 1, 1_000_000))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("question text");
    }

    @Test
    @DisplayName("Decoding rejects question-count metadata that disagrees with payload")
    void decodeRejectsQuestionCountMismatch() {
        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                Map.of(0, List.of(question("Question", QuestionType.OPEN, "{}"))),
                1_000_000
        );

        assertThatThrownBy(() -> codec.decode(1, encoded.payload(), 2, 1_000_000))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("question count");
    }

    private Question question(String text, QuestionType type, String content) {
        Question question = new Question();
        question.setType(type);
        question.setDifficulty(Difficulty.HARD);
        question.setQuestionText(text);
        question.setContent(content);
        return question;
    }
}
