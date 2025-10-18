package uk.gegc.quizmaker.features.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.application.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify ORDERING questions preserve correct order through the entire flow:
 * AI generation → conversion → shuffling → database persistence → retrieval
 * 
 * This test simulates the AI returning items in CORRECT chronological order and verifies
 * that the correctOrder field captures this order correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ORDERING Question Correctness Integration Tests")
class OrderingQuestionCorrectnessIntegrationTest {

    @Autowired
    private AiQuizGenerationServiceImpl aiQuizGenerationService;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Clean up any existing questions
        questionRepository.deleteAll();
    }

    @Test
    @DisplayName("convertStructuredQuestions: when AI returns ORDERING in correct chronological order then correctOrder preserves it")
    void convertStructuredQuestions_orderingInCorrectOrder_preservesCorrectOrder() throws Exception {
        // Given - Simulate AI returning SSL/TLS versions in CORRECT chronological order
        StructuredQuestion structuredQuestion = new StructuredQuestion();
        structuredQuestion.setQuestionText("Arrange the following SSL/TLS versions in chronological order:");
        structuredQuestion.setType(QuestionType.ORDERING);
        structuredQuestion.setDifficulty(Difficulty.MEDIUM);
        
        // CRITICAL: AI returns items in CORRECT chronological order with sequential IDs
        structuredQuestion.setContent("""
            {
                "items": [
                    {"id": 1, "text": "SSL 2.0 (1995)"},
                    {"id": 2, "text": "SSL 3.0 (1996)"},
                    {"id": 3, "text": "TLS 1.0 (1999)"},
                    {"id": 4, "text": "TLS 1.1 (2006)"},
                    {"id": 5, "text": "TLS 1.2 (2008)"},
                    {"id": 6, "text": "TLS 1.3 (2018)"}
                ]
            }
            """);
        structuredQuestion.setHint("Look at the years in parentheses");
        structuredQuestion.setExplanation("SSL/TLS versions were released in chronological order from SSL 2.0 in 1995 to TLS 1.3 in 2018");

        List<StructuredQuestion> structuredQuestions = List.of(structuredQuestion);

        // When - Convert through the service (which applies shuffling)
        List<Question> questions = aiQuizGenerationService.convertStructuredQuestions(structuredQuestions);

        // Then - Verify question was created
        assertThat(questions).hasSize(1);
        Question question = questions.get(0);
        
        // Verify basic properties
        assertThat(question.getQuestionText()).isEqualTo("Arrange the following SSL/TLS versions in chronological order:");
        assertThat(question.getType()).isEqualTo(QuestionType.ORDERING);
        assertThat(question.getDifficulty()).isEqualTo(Difficulty.MEDIUM);

        // Parse the content
        JsonNode content = objectMapper.readTree(question.getContent());
        JsonNode items = content.get("items");
        JsonNode correctOrder = content.get("correctOrder");

        // Verify correctOrder field exists
        assertThat(correctOrder).isNotNull();
        assertThat(correctOrder.isArray()).isTrue();
        assertThat(correctOrder.size()).isEqualTo(6);

        // Build map of id -> text from items
        java.util.Map<Integer, String> idToText = new java.util.HashMap<>();
        for (JsonNode item : items) {
            idToText.put(item.get("id").asInt(), item.get("text").asText());
        }

        // CRITICAL ASSERTION: Verify correctOrder points to items in CORRECT chronological sequence
        // This checks the ACTUAL TEXT CONTENT, not just IDs
        assertThat(idToText.get(correctOrder.get(0).asInt())).isEqualTo("SSL 2.0 (1995)");
        assertThat(idToText.get(correctOrder.get(1).asInt())).isEqualTo("SSL 3.0 (1996)");
        assertThat(idToText.get(correctOrder.get(2).asInt())).isEqualTo("TLS 1.0 (1999)");
        assertThat(idToText.get(correctOrder.get(3).asInt())).isEqualTo("TLS 1.1 (2006)");
        assertThat(idToText.get(correctOrder.get(4).asInt())).isEqualTo("TLS 1.2 (2008)");
        assertThat(idToText.get(correctOrder.get(5).asInt())).isEqualTo("TLS 1.3 (2018)");

        // Verify items array is shuffled (should NOT be in original order)
        assertThat(items).isNotNull();
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(6);

        // Collect item IDs from shuffled array
        List<Integer> shuffledIds = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            shuffledIds.add(item.get("id").asInt());
        }

        // Items should be shuffled (not in original 1,2,3,4,5,6 order)
        assertThat(shuffledIds).isNotEqualTo(List.of(1, 2, 3, 4, 5, 6));
        
        // But all IDs should still be present (no data loss)
        assertThat(shuffledIds).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("convertStructuredQuestions: when AI returns process steps in correct order then correctOrder preserves sequence")
    void convertStructuredQuestions_processStepsInCorrectOrder_preservesCorrectOrder() throws Exception {
        // Given - Simulate AI returning scientific process in CORRECT order
        StructuredQuestion structuredQuestion = new StructuredQuestion();
        structuredQuestion.setQuestionText("Arrange the steps of photosynthesis in the correct order:");
        structuredQuestion.setType(QuestionType.ORDERING);
        structuredQuestion.setDifficulty(Difficulty.HARD);
        
        // AI returns process steps in CORRECT order with sequential IDs
        structuredQuestion.setContent("""
            {
                "items": [
                    {"id": 1, "text": "Light energy is absorbed by chlorophyll"},
                    {"id": 2, "text": "Water molecules are split into hydrogen and oxygen"},
                    {"id": 3, "text": "ATP and NADPH are produced"},
                    {"id": 4, "text": "Carbon dioxide is fixed in the Calvin cycle"},
                    {"id": 5, "text": "Glucose is synthesized"}
                ]
            }
            """);
        structuredQuestion.setHint("Think about light-dependent reactions first, then Calvin cycle");
        structuredQuestion.setExplanation("Photosynthesis proceeds in two phases");

        List<StructuredQuestion> structuredQuestions = List.of(structuredQuestion);

        // When
        List<Question> questions = aiQuizGenerationService.convertStructuredQuestions(structuredQuestions);

        // Then
        assertThat(questions).hasSize(1);
        Question question = questions.get(0);

        JsonNode content = objectMapper.readTree(question.getContent());
        JsonNode items = content.get("items");
        JsonNode correctOrder = content.get("correctOrder");

        // Verify correctOrder preserves the process sequence
        assertThat(correctOrder).isNotNull();
        assertThat(correctOrder.size()).isEqualTo(5);
        
        // Build map of id -> text
        java.util.Map<Integer, String> idToText = new java.util.HashMap<>();
        for (JsonNode item : items) {
            idToText.put(item.get("id").asInt(), item.get("text").asText());
        }
        
        // Verify correctOrder points to correct process sequence
        assertThat(idToText.get(correctOrder.get(0).asInt())).isEqualTo("Light energy is absorbed by chlorophyll");
        assertThat(idToText.get(correctOrder.get(1).asInt())).isEqualTo("Water molecules are split into hydrogen and oxygen");
        assertThat(idToText.get(correctOrder.get(2).asInt())).isEqualTo("ATP and NADPH are produced");
        assertThat(idToText.get(correctOrder.get(3).asInt())).isEqualTo("Carbon dioxide is fixed in the Calvin cycle");
        assertThat(idToText.get(correctOrder.get(4).asInt())).isEqualTo("Glucose is synthesized");
    }

    @Test
    @DisplayName("Full flow: ORDERING question persisted to DB preserves correctOrder after retrieval")
    void fullFlow_orderingQuestion_preservesCorrectOrderInDatabase() throws Exception {
        // Given - Create and convert an ORDERING question
        StructuredQuestion structuredQuestion = new StructuredQuestion();
        structuredQuestion.setQuestionText("Arrange these historical events in order:");
        structuredQuestion.setType(QuestionType.ORDERING);
        structuredQuestion.setDifficulty(Difficulty.EASY);
        
        // AI returns in CORRECT chronological order
        structuredQuestion.setContent("""
            {
                "items": [
                    {"id": 1, "text": "Declaration of Independence (1776)"},
                    {"id": 2, "text": "Constitution ratified (1788)"},
                    {"id": 3, "text": "Bill of Rights (1791)"},
                    {"id": 4, "text": "Civil War begins (1861)"}
                ]
            }
            """);
        structuredQuestion.setHint("Check the dates");
        structuredQuestion.setExplanation("Events occurred in chronological order");

        // When - Convert and save to database
        List<Question> questions = aiQuizGenerationService.convertStructuredQuestions(List.of(structuredQuestion));
        Question savedQuestion = questionRepository.save(questions.get(0));
        questionRepository.flush();

        // Clear persistence context to force fresh query
        questionRepository.findAll(); // Just to ensure flush

        // Retrieve from database
        Question retrievedQuestion = questionRepository.findById(savedQuestion.getId()).orElseThrow();

        // Then - Verify correctOrder is preserved after full round-trip
        JsonNode content = objectMapper.readTree(retrievedQuestion.getContent());
        JsonNode correctOrder = content.get("correctOrder");

        assertThat(correctOrder).isNotNull();
        assertThat(correctOrder.isArray()).isTrue();
        assertThat(correctOrder.size()).isEqualTo(4);

        // CRITICAL: correctOrder must match the original chronological sequence
        assertThat(correctOrder.get(0).asInt()).isEqualTo(1); // 1776
        assertThat(correctOrder.get(1).asInt()).isEqualTo(2); // 1788
        assertThat(correctOrder.get(2).asInt()).isEqualTo(3); // 1791
        assertThat(correctOrder.get(3).asInt()).isEqualTo(4); // 1861

        // Verify items are shuffled but all present
        JsonNode items = content.get("items");
        assertThat(items.size()).isEqualTo(4);
        
        List<Integer> itemIds = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            itemIds.add(item.get("id").asInt());
        }
        assertThat(itemIds).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    @DisplayName("convertStructuredQuestions: when AI returns items in WRONG order then correctOrder captures that WRONG order (demonstrates the problem)")
    void convertStructuredQuestions_wrongAiOrder_capturesWrongOrder() throws Exception {
        // Given - Simulate AI returning items in WRONG order with random IDs
        // This reproduces the ACTUAL PROBLEM from your database
        StructuredQuestion structuredQuestion = new StructuredQuestion();
        structuredQuestion.setQuestionText("Arrange SSL/TLS versions in order:");
        structuredQuestion.setType(QuestionType.ORDERING);
        structuredQuestion.setDifficulty(Difficulty.MEDIUM);
        
        // AI mistakenly returns in WRONG chronological order (matching your database example)
        structuredQuestion.setContent("""
            {
                "items": [
                    {"id": 1, "text": "TLS 1.0 (1999)"},
                    {"id": 2, "text": "TLS 1.3 (2018)"},
                    {"id": 3, "text": "TLS 1.1 (2006)"},
                    {"id": 4, "text": "SSL 2.0 (1995)"},
                    {"id": 5, "text": "SSL 3.0 (1996)"},
                    {"id": 6, "text": "TLS 1.2 (2008)"}
                ]
            }
            """);
        structuredQuestion.setHint("Look at the years");
        structuredQuestion.setExplanation("Chronological order");

        List<StructuredQuestion> structuredQuestions = List.of(structuredQuestion);

        // When
        List<Question> questions = aiQuizGenerationService.convertStructuredQuestions(structuredQuestions);

        // Then
        assertThat(questions).hasSize(1);
        Question question = questions.get(0);

        JsonNode content = objectMapper.readTree(question.getContent());
        JsonNode items = content.get("items");
        JsonNode correctOrder = content.get("correctOrder");

        // Build map of id -> text
        java.util.Map<Integer, String> idToText = new java.util.HashMap<>();
        for (JsonNode item : items) {
            idToText.put(item.get("id").asInt(), item.get("text").asText());
        }

        // This demonstrates the PROBLEM: correctOrder captures the WRONG order from AI
        assertThat(correctOrder).isNotNull();
        assertThat(correctOrder.size()).isEqualTo(6);
        
        // The correctOrder will be [1, 2, 3, 4, 5, 6] but this maps to WRONG chronological order:
        assertThat(idToText.get(correctOrder.get(0).asInt())).isEqualTo("TLS 1.0 (1999)"); // WRONG! Should be SSL 2.0 (1995)
        assertThat(idToText.get(correctOrder.get(1).asInt())).isEqualTo("TLS 1.3 (2018)"); // WRONG! Should be SSL 3.0 (1996)
        assertThat(idToText.get(correctOrder.get(2).asInt())).isEqualTo("TLS 1.1 (2006)"); // WRONG! Should be TLS 1.0 (1999)
        assertThat(idToText.get(correctOrder.get(3).asInt())).isEqualTo("SSL 2.0 (1995)"); // WRONG! This is the earliest, should be first!
        assertThat(idToText.get(correctOrder.get(4).asInt())).isEqualTo("SSL 3.0 (1996)"); // WRONG! Should be TLS 1.2 (2008)
        assertThat(idToText.get(correctOrder.get(5).asInt())).isEqualTo("TLS 1.2 (2008)"); // WRONG! Should be TLS 1.3 (2018)
        
        // THE PROBLEM: Our code correctly captures what AI provides, but AI provides wrong order!
        // Solution: Fix the AI prompts to be more explicit (which we've now done)
    }

    @Test
    @DisplayName("Full round-trip: when AI returns correct order then database query returns correct order")
    void fullRoundTrip_correctAiOrder_databasePreservesCorrectOrder() throws Exception {
        // Given - Create ORDERING question with items in CORRECT order
        StructuredQuestion structuredQuestion = new StructuredQuestion();
        structuredQuestion.setQuestionText("Order these programming languages by year of first release:");
        structuredQuestion.setType(QuestionType.ORDERING);
        structuredQuestion.setDifficulty(Difficulty.MEDIUM);
        
        // AI correctly returns in chronological order
        structuredQuestion.setContent("""
            {
                "items": [
                    {"id": 1, "text": "C (1972)"},
                    {"id": 2, "text": "C++ (1985)"},
                    {"id": 3, "text": "Python (1991)"},
                    {"id": 4, "text": "Java (1995)"},
                    {"id": 5, "text": "JavaScript (1995)"}
                ]
            }
            """);
        structuredQuestion.setHint("Check the release years");
        structuredQuestion.setExplanation("Languages listed in order of first release");

        // When - Full flow: convert → save → flush → query
        List<Question> convertedQuestions = aiQuizGenerationService.convertStructuredQuestions(List.of(structuredQuestion));
        Question savedQuestion = questionRepository.saveAndFlush(convertedQuestions.get(0));
        
        // Retrieve from database (simulates what happens when user starts quiz)
        Question retrievedQuestion = questionRepository.findById(savedQuestion.getId()).orElseThrow();

        // Then - Verify correctOrder matches original chronological sequence
        JsonNode content = objectMapper.readTree(retrievedQuestion.getContent());
        JsonNode correctOrder = content.get("correctOrder");
        
        assertThat(correctOrder).isNotNull();
        assertThat(correctOrder.size()).isEqualTo(5);
        
        // This should be the CORRECT chronological sequence
        assertThat(correctOrder.get(0).asInt()).isEqualTo(1); // C (1972)
        assertThat(correctOrder.get(1).asInt()).isEqualTo(2); // C++ (1985)
        assertThat(correctOrder.get(2).asInt()).isEqualTo(3); // Python (1991)
        assertThat(correctOrder.get(3).asInt()).isEqualTo(4); // Java (1995)
        assertThat(correctOrder.get(4).asInt()).isEqualTo(5); // JavaScript (1995)
        
        // Verify we can map IDs back to actual items
        JsonNode items = content.get("items");
        assertThat(items.size()).isEqualTo(5);
        
        // Build a map of id -> text from shuffled items
        java.util.Map<Integer, String> idToText = new java.util.HashMap<>();
        for (JsonNode item : items) {
            idToText.put(item.get("id").asInt(), item.get("text").asText());
        }
        
        // Verify correctOrder IDs map to correct chronological sequence
        assertThat(idToText.get(correctOrder.get(0).asInt())).isEqualTo("C (1972)");
        assertThat(idToText.get(correctOrder.get(1).asInt())).isEqualTo("C++ (1985)");
        assertThat(idToText.get(correctOrder.get(2).asInt())).isEqualTo("Python (1991)");
        assertThat(idToText.get(correctOrder.get(3).asInt())).isEqualTo("Java (1995)");
        assertThat(idToText.get(correctOrder.get(4).asInt())).isEqualTo("JavaScript (1995)");
    }

    @Test
    @DisplayName("Multiple ORDERING questions: each preserves its own correct order independently")
    void multipleOrderingQuestions_eachPreservesOwnCorrectOrder() throws Exception {
        // Given - Create two different ORDERING questions
        StructuredQuestion question1 = new StructuredQuestion();
        question1.setQuestionText("Order these numbers:");
        question1.setType(QuestionType.ORDERING);
        question1.setDifficulty(Difficulty.EASY);
        question1.setContent("""
            {
                "items": [
                    {"id": 1, "text": "One"},
                    {"id": 2, "text": "Two"},
                    {"id": 3, "text": "Three"}
                ]
            }
            """);
        question1.setHint("Count");
        question1.setExplanation("Sequential numbers");

        StructuredQuestion question2 = new StructuredQuestion();
        question2.setQuestionText("Order these letters:");
        question2.setType(QuestionType.ORDERING);
        question2.setDifficulty(Difficulty.EASY);
        question2.setContent("""
            {
                "items": [
                    {"id": 1, "text": "Alpha"},
                    {"id": 2, "text": "Beta"},
                    {"id": 3, "text": "Gamma"},
                    {"id": 4, "text": "Delta"}
                ]
            }
            """);
        question2.setHint("Greek alphabet");
        question2.setExplanation("First four Greek letters");

        // When
        List<Question> questions = aiQuizGenerationService.convertStructuredQuestions(List.of(question1, question2));
        questionRepository.saveAll(questions);
        questionRepository.flush();

        // Then - Verify each question has its own correct order
        Question q1 = questions.get(0);
        Question q2 = questions.get(1);

        JsonNode content1 = objectMapper.readTree(q1.getContent());
        JsonNode correctOrder1 = content1.get("correctOrder");
        assertThat(correctOrder1.size()).isEqualTo(3);
        assertThat(correctOrder1.get(0).asInt()).isEqualTo(1);
        assertThat(correctOrder1.get(1).asInt()).isEqualTo(2);
        assertThat(correctOrder1.get(2).asInt()).isEqualTo(3);

        JsonNode content2 = objectMapper.readTree(q2.getContent());
        JsonNode correctOrder2 = content2.get("correctOrder");
        assertThat(correctOrder2.size()).isEqualTo(4);
        assertThat(correctOrder2.get(0).asInt()).isEqualTo(1);
        assertThat(correctOrder2.get(1).asInt()).isEqualTo(2);
        assertThat(correctOrder2.get(2).asInt()).isEqualTo(3);
        assertThat(correctOrder2.get(3).asInt()).isEqualTo(4);
    }
}

