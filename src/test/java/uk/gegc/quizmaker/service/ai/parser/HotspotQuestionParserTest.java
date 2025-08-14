package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class HotspotQuestionParserTest {

    private HotspotQuestionParser parser;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        parser = new HotspotQuestionParser();
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldParseValidHotspotQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area in the diagram",
                  "difficulty": "MEDIUM",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/diagram.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 20, "width": 30, "height": 40, "correct": true},
                      {"id": 2, "x": 50, "y": 60, "width": 25, "height": 35, "correct": false},
                      {"id": 3, "x": 80, "y": 90, "width": 20, "height": 30, "correct": false}
                    ]
                  },
                  "hint": "Look for the area that represents the main component",
                  "explanation": "The correct area is the largest region which represents the primary element"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Click on the correct area in the diagram", question.getQuestionText());
        assertEquals(QuestionType.HOTSPOT, question.getType());
        assertEquals(Difficulty.MEDIUM, question.getDifficulty());
        assertEquals("Look for the area that represents the main component", question.getHint());
        assertEquals("The correct area is the largest region which represents the primary element", question.getExplanation());
        
        // Verify content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("imageUrl"));
        assertEquals("https://example.com/diagram.png", content.get("imageUrl").asText());
        assertTrue(content.has("regions"));
        assertEquals(3, content.get("regions").size());
        
        // Verify regions
        JsonNode region1 = content.get("regions").get(0);
        assertEquals(1, region1.get("id").asInt());
        assertEquals(10, region1.get("x").asInt());
        assertEquals(20, region1.get("y").asInt());
        assertEquals(30, region1.get("width").asInt());
        assertEquals(40, region1.get("height").asInt());
        assertTrue(region1.get("correct").asBoolean());
        
        JsonNode region2 = content.get("regions").get(1);
        assertEquals(2, region2.get("id").asInt());
        assertEquals(50, region2.get("x").asInt());
        assertEquals(60, region2.get("y").asInt());
        assertEquals(25, region2.get("width").asInt());
        assertEquals(35, region2.get("height").asInt());
        assertFalse(region2.get("correct").asBoolean());
    }

    @Test
    void shouldParseMultipleHotspotQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Identify the correct area in the flowchart",
                  "difficulty": "EASY",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/flowchart.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 50, "height": 50, "correct": true},
                      {"id": 2, "x": 70, "y": 10, "width": 50, "height": 50, "correct": false}
                    ]
                  }
                },
                {
                  "questionText": "Click on the specific component in the circuit diagram",
                  "difficulty": "HARD",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/circuit.png",
                    "regions": [
                      {"id": 1, "x": 5, "y": 5, "width": 10, "height": 10, "correct": false},
                      {"id": 2, "x": 20, "y": 5, "width": 10, "height": 10, "correct": true},
                      {"id": 3, "x": 35, "y": 5, "width": 10, "height": 10, "correct": false},
                      {"id": 4, "x": 50, "y": 5, "width": 10, "height": 10, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());
        
        // First question
        Question question1 = questions.get(0);
        assertEquals("Identify the correct area in the flowchart", question1.getQuestionText());
        assertEquals(Difficulty.EASY, question1.getDifficulty());
        
        JsonNode content1 = objectMapper.readTree(question1.getContent());
        assertEquals("https://example.com/flowchart.png", content1.get("imageUrl").asText());
        assertEquals(2, content1.get("regions").size());
        
        // Second question
        Question question2 = questions.get(1);
        assertEquals("Click on the specific component in the circuit diagram", question2.getQuestionText());
        assertEquals(Difficulty.HARD, question2.getDifficulty());
        
        JsonNode content2 = objectMapper.readTree(question2.getContent());
        assertEquals("https://example.com/circuit.png", content2.get("imageUrl").asText());
        assertEquals(4, content2.get("regions").size());
    }

    @Test
    void shouldHandleMissingDifficulty() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
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

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        assertEquals(Difficulty.MEDIUM, questions.get(0).getDifficulty()); // Default difficulty
    }

    @Test
    void shouldHandleInvalidDifficulty() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "difficulty": "INVALID_DIFFICULTY",
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

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseHotspotQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        assertEquals(Difficulty.MEDIUM, questions.get(0).getDifficulty()); // Default difficulty
    }

    @Test
    void shouldThrowExceptionForMissingContent() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT"
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingImageUrl() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyImageUrl() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingRegions() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png"
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyRegions() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": []
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForSingleRegion() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": true}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForTooManyRegions() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false},
                      {"id": 3, "x": 90, "y": 90, "width": 30, "height": 30, "correct": false},
                      {"id": 4, "x": 130, "y": 130, "width": 30, "height": 30, "correct": false},
                      {"id": 5, "x": 170, "y": 170, "width": 30, "height": 30, "correct": false},
                      {"id": 6, "x": 210, "y": 210, "width": 30, "height": 30, "correct": false},
                      {"id": 7, "x": 250, "y": 250, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingRegionId() {
        // Given
        String jsonContent = """
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
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonIntegerRegionId() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": "one", "x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingCoordinate() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonIntegerCoordinate() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": "ten", "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNegativeCoordinate() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": -10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingCorrectFlag() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonBooleanCorrectFlag() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": "yes"},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForDuplicateRegionIds() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": true},
                      {"id": 1, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNoCorrectRegions() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Click on the correct area",
                  "type": "HOTSPOT",
                  "content": {
                    "imageUrl": "https://example.com/image.png",
                    "regions": [
                      {"id": 1, "x": 10, "y": 10, "width": 30, "height": 30, "correct": false},
                      {"id": 2, "x": 50, "y": 50, "width": 30, "height": 30, "correct": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseHotspotQuestions(contentNode);
        });
    }

    @Test
    void shouldHandleEmptyQuestionsArray() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": []
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseHotspotQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }

    @Test
    void shouldHandleMissingQuestionsArray() throws Exception {
        // Given
        String jsonContent = """
            {
              "otherField": "value"
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseHotspotQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }
} 