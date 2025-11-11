package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for QuestionContentShuffler.
 * 
 * Tests deterministic shuffling behavior for different question types
 * using seeded random number generators to ensure reproducible results.
 */
@ExtendWith(MockitoExtension.class)
class QuestionContentShufflerTest {

    private QuestionContentShuffler shuffler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        shuffler = new QuestionContentShuffler(objectMapper);
    }

    @Test
    @DisplayName("shuffleContent: when MCQ_SINGLE with options then shuffles options array")
    void shuffleContent_mcqSingle_shufflesOptions() throws Exception {
        // Given
        String contentJson = """
            {
                "options": [
                    {"id": 1, "text": "Option A", "correct": true},
                    {"id": 2, "text": "Option B", "correct": false},
                    {"id": 3, "text": "Option C", "correct": false},
                    {"id": 4, "text": "Option D", "correct": false}
                ]
            }
            """;
        
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.MCQ_SINGLE, randomSupplier);

        // Then
        JsonNode resultNode = objectMapper.readTree(result);
        JsonNode options = resultNode.get("options");
        
        assertThat(options).isNotNull();
        assertThat(options.isArray()).isTrue();
        assertThat(options.size()).isEqualTo(4);
        
        // Verify all options are preserved with their properties
        for (JsonNode option : options) {
            assertThat(option.has("id")).isTrue();
            assertThat(option.has("text")).isTrue();
            assertThat(option.has("correct")).isTrue();
        }
        
        // Verify order changed (just check that it's different from original)
        List<Integer> shuffledIds = new ArrayList<>();
        for (JsonNode option : options) {
            shuffledIds.add(option.get("id").asInt());
        }
        // Original order was [1, 2, 3, 4], so shuffled should be different
        assertThat(shuffledIds).isNotEqualTo(List.of(1, 2, 3, 4));
    }

    @Test
    @DisplayName("shuffleContent: when ORDERING with items then shuffles items and adds correctOrder")
    void shuffleContent_ordering_shufflesItemsAndAddsCorrectOrder() throws Exception {
        // Given
        String contentJson = """
            {
                "items": [
                    {"id": 1, "text": "First step"},
                    {"id": 2, "text": "Second step"},
                    {"id": 3, "text": "Third step"},
                    {"id": 4, "text": "Fourth step"}
                ]
            }
            """;
        
        Random seededRandom = new Random(54321L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.ORDERING, randomSupplier);

        // Then
        JsonNode resultNode = objectMapper.readTree(result);
        JsonNode items = resultNode.get("items");
        JsonNode correctOrder = resultNode.get("correctOrder");
        
        assertThat(items).isNotNull();
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(4);
        
        assertThat(correctOrder).isNotNull();
        assertThat(correctOrder.isArray()).isTrue();
        assertThat(correctOrder.size()).isEqualTo(4);
        
        // CRITICAL: Verify correctOrder contains original AI sequence (captured BEFORE shuffle)
        assertThat(correctOrder.get(0).asInt()).isEqualTo(1);
        assertThat(correctOrder.get(1).asInt()).isEqualTo(2);
        assertThat(correctOrder.get(2).asInt()).isEqualTo(3);
        assertThat(correctOrder.get(3).asInt()).isEqualTo(4);
        
        // Verify items ARE shuffled (different from original to remove AI bias)
        List<Integer> shuffledItemIds = new ArrayList<>();
        for (JsonNode item : items) {
            shuffledItemIds.add(item.get("id").asInt());
        }
        // Original order was [1, 2, 3, 4], shuffled should be different
        assertThat(shuffledItemIds).isNotEqualTo(List.of(1, 2, 3, 4));
    }

    @Test
    @DisplayName("shuffleContent: when MATCHING with left and right then shuffles right column only")
    void shuffleContent_matching_shufflesRightColumnOnly() throws Exception {
        // Given
        String contentJson = """
            {
                "left": [
                    {"id": 1, "text": "Left 1", "matchId": 10},
                    {"id": 2, "text": "Left 2", "matchId": 20}
                ],
                "right": [
                    {"id": 10, "text": "Right 1"},
                    {"id": 20, "text": "Right 2"},
                    {"id": 30, "text": "Right 3"}
                ]
            }
            """;
        
        Random seededRandom = new Random(99999L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.MATCHING, randomSupplier);

        // Then
        JsonNode resultNode = objectMapper.readTree(result);
        JsonNode left = resultNode.get("left");
        JsonNode right = resultNode.get("right");
        
        assertThat(left).isNotNull();
        assertThat(right).isNotNull();
        assertThat(right.isArray()).isTrue();
        assertThat(right.size()).isEqualTo(3);
        
        // Verify left column is unchanged
        assertThat(left.get(0).get("matchId").asInt()).isEqualTo(10);
        assertThat(left.get(1).get("matchId").asInt()).isEqualTo(20);
        
        // Verify right column is shuffled (just check that it's different from original)
        List<Integer> shuffledRightIds = new ArrayList<>();
        for (JsonNode rightItem : right) {
            shuffledRightIds.add(rightItem.get("id").asInt());
        }
        // Original order was [10, 20, 30], so shuffled should be different
        assertThat(shuffledRightIds).isNotEqualTo(List.of(10, 20, 30));
    }

    @Test
    @DisplayName("shuffleContent: when COMPLIANCE with statements then shuffles statements array")
    void shuffleContent_compliance_shufflesStatements() throws Exception {
        // Given
        String contentJson = """
            {
                "statements": [
                    {"id": 1, "text": "Statement 1", "compliant": true},
                    {"id": 2, "text": "Statement 2", "compliant": false},
                    {"id": 3, "text": "Statement 3", "compliant": true}
                ]
            }
            """;
        
        Random seededRandom = new Random(77777L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.COMPLIANCE, randomSupplier);

        // Then
        JsonNode resultNode = objectMapper.readTree(result);
        JsonNode statements = resultNode.get("statements");
        
        assertThat(statements).isNotNull();
        assertThat(statements.isArray()).isTrue();
        assertThat(statements.size()).isEqualTo(3);
        
        // Verify all statements preserved with properties
        for (JsonNode statement : statements) {
            assertThat(statement.has("id")).isTrue();
            assertThat(statement.has("text")).isTrue();
            assertThat(statement.has("compliant")).isTrue();
        }
        
        // Verify order changed (just check that it's different from original)
        List<Integer> shuffledStatementIds = new ArrayList<>();
        for (JsonNode statement : statements) {
            shuffledStatementIds.add(statement.get("id").asInt());
        }
        // Original order was [1, 2, 3], so shuffled should be different
        assertThat(shuffledStatementIds).isNotEqualTo(List.of(1, 2, 3));
    }

    @Test
    @DisplayName("shuffleContent: when HOTSPOT with regions then shuffles regions array")
    void shuffleContent_hotspot_shufflesRegions() throws Exception {
        // Given
        String contentJson = """
            {
                "regions": [
                    {"id": 1, "x": 10, "y": 20, "width": 30, "height": 40, "correct": true},
                    {"id": 2, "x": 50, "y": 60, "width": 70, "height": 80, "correct": false},
                    {"id": 3, "x": 90, "y": 100, "width": 110, "height": 120, "correct": false}
                ]
            }
            """;
        
        Random seededRandom = new Random(11111L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.HOTSPOT, randomSupplier);

        // Then
        JsonNode resultNode = objectMapper.readTree(result);
        JsonNode regions = resultNode.get("regions");
        
        assertThat(regions).isNotNull();
        assertThat(regions.isArray()).isTrue();
        assertThat(regions.size()).isEqualTo(3);
        
        // Verify all regions preserved with properties
        for (JsonNode region : regions) {
            assertThat(region.has("id")).isTrue();
            assertThat(region.has("correct")).isTrue();
        }
        
        // Verify order changed (just check that it's different from original)
        List<Integer> shuffledRegionIds = new ArrayList<>();
        for (JsonNode region : regions) {
            shuffledRegionIds.add(region.get("id").asInt());
        }
        // Original order was [1, 2, 3], so shuffled should be different
        assertThat(shuffledRegionIds).isNotEqualTo(List.of(1, 2, 3));
    }

    @Test
    @DisplayName("shuffleContent: when TRUE_FALSE then no shuffling applied")
    void shuffleContent_trueFalse_noShuffling() throws Exception {
        // Given
        String contentJson = """
            {
                "statement": "This is a true statement",
                "correct": true
            }
            """;
        
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.TRUE_FALSE, randomSupplier);

        // Then
        // Parse both to compare structure, not formatting
        JsonNode original = objectMapper.readTree(contentJson);
        JsonNode resultNode = objectMapper.readTree(result);
        assertThat(resultNode).isEqualTo(original); // Should be unchanged
    }

    @Test
    @DisplayName("shuffleContent: when FILL_GAP then no shuffling applied")
    void shuffleContent_fillGap_noShuffling() throws Exception {
        // Given
        String contentJson = """
            {
                "text": "Complete the sentence with the missing word: The capital of France is {1}.",
                "gaps": [
                    {"id": 1, "answer": "Paris"}
                ]
            }
            """;
        
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.FILL_GAP, randomSupplier);

        // Then
        // Parse both to compare structure, not formatting
        JsonNode original = objectMapper.readTree(contentJson);
        JsonNode resultNode = objectMapper.readTree(result);
        assertThat(resultNode).isEqualTo(original); // Should be unchanged
    }

    @Test
    @DisplayName("shuffleContent: when OPEN then no shuffling applied")
    void shuffleContent_open_noShuffling() throws Exception {
        // Given
        String contentJson = """
            {
                "question": "Explain the concept of object-oriented programming.",
                "maxLength": 500
            }
            """;
        
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.OPEN, randomSupplier);

        // Then
        // Parse both to compare structure, not formatting
        JsonNode original = objectMapper.readTree(contentJson);
        JsonNode resultNode = objectMapper.readTree(result);
        assertThat(resultNode).isEqualTo(original); // Should be unchanged
    }

    @Test
    @DisplayName("shuffleContent: when null content then returns null")
    void shuffleContent_nullContent_returnsNull() {
        // Given
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(null, QuestionType.MCQ_SINGLE, randomSupplier);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("shuffleContent: when blank content then returns blank")
    void shuffleContent_blankContent_returnsBlank() {
        // Given
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent("   ", QuestionType.MCQ_SINGLE, randomSupplier);

        // Then
        assertThat(result).isEqualTo("   ");
    }

    @Test
    @DisplayName("shuffleContent: when malformed JSON then returns original content")
    void shuffleContent_malformedJson_returnsOriginal() {
        // Given
        String malformedJson = "{ invalid json }";
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(malformedJson, QuestionType.MCQ_SINGLE, randomSupplier);

        // Then
        assertThat(result).isEqualTo(malformedJson); // Should return original on error
    }

    @Test
    @DisplayName("shuffleContent: when non-object JSON then returns original content")
    void shuffleContent_nonObjectJson_returnsOriginal() {
        // Given
        String arrayJson = "[1, 2, 3]";
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(arrayJson, QuestionType.MCQ_SINGLE, randomSupplier);

        // Then
        assertThat(result).isEqualTo(arrayJson); // Should return original
    }

    @Test
    @DisplayName("shuffleContent: when MCQ with missing options then no shuffling")
    void shuffleContent_mcqMissingOptions_noShuffling() throws Exception {
        // Given
        String contentJson = """
            {
                "question": "What is the capital of France?",
                "explanation": "Paris is the capital"
            }
            """;
        
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.MCQ_SINGLE, randomSupplier);

        // Then
        // Parse both to compare structure, not formatting
        JsonNode original = objectMapper.readTree(contentJson);
        JsonNode resultNode = objectMapper.readTree(result);
        assertThat(resultNode).isEqualTo(original); // Should be unchanged
    }

    @Test
    @DisplayName("shuffleContent: when ORDERING with missing items then no shuffling")
    void shuffleContent_orderingMissingItems_noShuffling() throws Exception {
        // Given
        String contentJson = """
            {
                "question": "Arrange the steps in order",
                "explanation": "Follow the sequence"
            }
            """;
        
        Random seededRandom = new Random(12345L);
        Supplier<Random> randomSupplier = () -> seededRandom;

        // When
        String result = shuffler.shuffleContent(contentJson, QuestionType.ORDERING, randomSupplier);

        // Then
        // Parse both to compare structure, not formatting
        JsonNode original = objectMapper.readTree(contentJson);
        JsonNode resultNode = objectMapper.readTree(result);
        assertThat(resultNode).isEqualTo(original); // Should be unchanged
    }

    @Test
    @DisplayName("shuffleContent: when deterministic random then produces same result")
    void shuffleContent_deterministicRandom_producesSameResult() throws Exception {
        // Given
        String contentJson = """
            {
                "options": [
                    {"id": 1, "text": "Option A", "correct": true},
                    {"id": 2, "text": "Option B", "correct": false},
                    {"id": 3, "text": "Option C", "correct": false}
                ]
            }
            """;
        
        Random seededRandom1 = new Random(12345L);
        Random seededRandom2 = new Random(12345L);
        Supplier<Random> randomSupplier1 = () -> seededRandom1;
        Supplier<Random> randomSupplier2 = () -> seededRandom2;

        // When
        String result1 = shuffler.shuffleContent(contentJson, QuestionType.MCQ_SINGLE, randomSupplier1);
        String result2 = shuffler.shuffleContent(contentJson, QuestionType.MCQ_SINGLE, randomSupplier2);

        // Then
        assertThat(result1).isEqualTo(result2); // Should be identical with same seed
    }
}
