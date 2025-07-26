package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.parser.HotspotQuestionParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
class HotspotQuestionGenerationIntegrationTest {

    @Autowired
    private HotspotQuestionParser hotspotQuestionParser;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldGenerateHotspotQuestionsFromContent() throws Exception {
        // Given - Sample content that would be used for HOTSPOT questions
        String sampleContent = """
            Network Architecture Diagram:
            The diagram shows a typical client-server architecture with the following components:
            - Client machines (top left)
            - Load balancer (top center)
            - Web servers (middle)
            - Database servers (bottom)
            - Firewall (surrounding the internal network)
            
            System Components:
            - Frontend: User interface components
            - Backend: Business logic and data processing
            - Database: Data storage and retrieval
            - API Gateway: Request routing and authentication
            """;

        // When - Simulate AI response for HOTSPOT questions
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Click on the load balancer in the network diagram",
                  "difficulty": "MEDIUM",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/network-diagram.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 20, "width": 30, "height": 40, "correct": true},
                      {"id": 2, "x": 50, "y": 60, "width": 25, "height": 35, "correct": false},
                      {"id": 3, "x": 80, "y": 90, "width": 20, "height": 30, "correct": false}
                    ]
                  },
                  "hint": "Look for the component that distributes traffic",
                  "explanation": "The load balancer is located in the top center area of the diagram"
                },
                {
                  "questionText": "Identify the database server location",
                  "difficulty": "EASY",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/network-diagram.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 50, "height": 50, "correct": true},
                      {"id": 2, "x": 70, "y": 10, "width": 50, "height": 50, "correct": false}
                    ]
                  },
                  "hint": "Database servers are typically located at the bottom of the architecture",
                  "explanation": "The database server is the large area at the bottom of the diagram"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = hotspotQuestionParser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());

        // Verify first question (Load balancer)
        Question loadBalancerQuestion = questions.get(0);
        assertEquals("Click on the load balancer in the network diagram", 
                     loadBalancerQuestion.getQuestionText());
        assertEquals(QuestionType.HOTSPOT, loadBalancerQuestion.getType());
        assertEquals(Difficulty.MEDIUM, loadBalancerQuestion.getDifficulty());
        assertEquals("Look for the component that distributes traffic", loadBalancerQuestion.getHint());
        assertEquals("The load balancer is located in the top center area of the diagram", 
                     loadBalancerQuestion.getExplanation());

        // Verify content structure for first question
        JsonNode content1 = objectMapper.readTree(loadBalancerQuestion.getContent());
        assertTrue(content1.has("imageUrl"));
        assertEquals("https://example.com/network-diagram.png", content1.get("imageUrl").asText());
        assertTrue(content1.has("regions"));
        assertEquals(3, content1.get("regions").size());
        
        // Verify regions are correctly marked
        JsonNode region1 = content1.get("regions").get(0);
        assertEquals(1, region1.get("id").asInt());
        assertEquals(10, region1.get("x").asInt());
        assertEquals(20, region1.get("y").asInt());
        assertEquals(30, region1.get("width").asInt());
        assertEquals(40, region1.get("height").asInt());
        assertTrue(region1.get("correct").asBoolean());
        
        JsonNode region2 = content1.get("regions").get(1);
        assertEquals(2, region2.get("id").asInt());
        assertEquals(50, region2.get("x").asInt());
        assertEquals(60, region2.get("y").asInt());
        assertEquals(25, region2.get("width").asInt());
        assertEquals(35, region2.get("height").asInt());
        assertFalse(region2.get("correct").asBoolean());

        // Verify second question (Database server)
        Question databaseQuestion = questions.get(1);
        assertEquals("Identify the database server location", databaseQuestion.getQuestionText());
        assertEquals(QuestionType.HOTSPOT, databaseQuestion.getType());
        assertEquals(Difficulty.EASY, databaseQuestion.getDifficulty());
        assertEquals("Database servers are typically located at the bottom of the architecture", databaseQuestion.getHint());
        assertEquals("The database server is the large area at the bottom of the diagram", 
                     databaseQuestion.getExplanation());

        // Verify content structure for second question
        JsonNode content2 = objectMapper.readTree(databaseQuestion.getContent());
        assertTrue(content2.has("imageUrl"));
        assertEquals("https://example.com/network-diagram.png", content2.get("imageUrl").asText());
        assertTrue(content2.has("regions"));
        assertEquals(2, content2.get("regions").size());
        
        // Verify regions are correctly marked
        JsonNode dbRegion1 = content2.get("regions").get(0);
        assertEquals(1, dbRegion1.get("id").asInt());
        assertEquals(10, dbRegion1.get("x").asInt());
        assertEquals(10, dbRegion1.get("y").asInt());
        assertEquals(50, dbRegion1.get("width").asInt());
        assertEquals(50, dbRegion1.get("height").asInt());
        assertTrue(dbRegion1.get("correct").asBoolean());
        
        JsonNode dbRegion2 = content2.get("regions").get(1);
        assertEquals(2, dbRegion2.get("id").asInt());
        assertEquals(70, dbRegion2.get("x").asInt());
        assertEquals(10, dbRegion2.get("y").asInt());
        assertEquals(50, dbRegion2.get("width").asInt());
        assertEquals(50, dbRegion2.get("height").asInt());
        assertFalse(dbRegion2.get("correct").asBoolean());
    }

    @Test
    void shouldHandleComplexHotspotScenarios() throws Exception {
        // Given - Complex hotspot scenario with multiple regions
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Click on the specific component in the detailed system architecture",
                  "difficulty": "HARD",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/detailed-architecture.png",
                    "regions": [
                      {"id": 1, "x": 5, "y": 5, "width": 10, "height": 10, "correct": false},
                      {"id": 2, "x": 20, "y": 5, "width": 10, "height": 10, "correct": true},
                      {"id": 3, "x": 35, "y": 5, "width": 10, "height": 10, "correct": false},
                      {"id": 4, "x": 50, "y": 5, "width": 10, "height": 10, "correct": false},
                      {"id": 5, "x": 65, "y": 5, "width": 10, "height": 10, "correct": false},
                      {"id": 6, "x": 80, "y": 5, "width": 10, "height": 10, "correct": false}
                    ]
                  },
                  "hint": "Look for the component that handles authentication",
                  "explanation": "The authentication service is the second component from the left"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = hotspotQuestionParser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Click on the specific component in the detailed system architecture", 
                     question.getQuestionText());
        assertEquals(QuestionType.HOTSPOT, question.getType());
        assertEquals(Difficulty.HARD, question.getDifficulty());

        // Verify complex content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("imageUrl"));
        assertEquals("https://example.com/detailed-architecture.png", content.get("imageUrl").asText());
        assertTrue(content.has("regions"));
        assertEquals(6, content.get("regions").size());

        // Verify all regions have unique IDs and proper structure
        for (int i = 0; i < 6; i++) {
            JsonNode region = content.get("regions").get(i);
            assertEquals(i + 1, region.get("id").asInt());
            assertTrue(region.get("x").asInt() >= 0);
            assertTrue(region.get("y").asInt() >= 0);
            assertTrue(region.get("width").asInt() > 0);
            assertTrue(region.get("height").asInt() > 0);
            assertTrue(region.has("correct"));
            assertTrue(region.get("correct").isBoolean());
        }

        // Verify exactly one region is correct
        int correctCount = 0;
        for (int i = 0; i < 6; i++) {
            JsonNode region = content.get("regions").get(i);
            if (region.get("correct").asBoolean()) {
                correctCount++;
            }
        }
        assertEquals(1, correctCount, "Exactly one region should be correct");
    }

    @Test
    void shouldValidateHotspotQuestionStructure() {
        // Given - Invalid structure with missing required fields
        String invalidResponse = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(Exception.class, () -> {
            JsonNode contentNode = objectMapper.readTree(invalidResponse);
            hotspotQuestionParser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldHandleEdgeCasesGracefully() throws Exception {
        // Given - Edge case with minimum valid regions
        String edgeCaseResponse = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "difficulty": "EASY",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(edgeCaseResponse);

        // When
        List<Question> questions = hotspotQuestionParser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(QuestionType.HOTSPOT, question.getType());
        assertEquals(Difficulty.EASY, question.getDifficulty());

        JsonNode content = objectMapper.readTree(question.getContent());
        assertEquals(2, content.get("regions").size());
        
        // Verify one region is correct and one is not
        boolean hasCorrect = false;
        boolean hasIncorrect = false;
        for (int i = 0; i < 2; i++) {
            JsonNode region = content.get("regions").get(i);
            if (region.get("correct").asBoolean()) {
                hasCorrect = true;
            } else {
                hasIncorrect = true;
            }
        }
        assertTrue(hasCorrect, "Should have at least one correct region");
        assertTrue(hasIncorrect, "Should have at least one incorrect region");
    }
} 