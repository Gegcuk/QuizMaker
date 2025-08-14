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
class ComplianceQuestionParserTest {

    private ComplianceQuestionParser parser;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        parser = new ComplianceQuestionParser();
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldParseValidComplianceQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate the following statements for GDPR compliance",
                  "difficulty": "MEDIUM",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Personal data is processed with explicit consent", "compliant": true},
                      {"id": 2, "text": "Data is stored indefinitely without purpose", "compliant": false},
                      {"id": 3, "text": "Users can request data deletion", "compliant": true},
                      {"id": 4, "text": "Data is shared with third parties without notification", "compliant": false}
                    ]
                  },
                  "hint": "Consider GDPR principles of consent, purpose limitation, and user rights",
                  "explanation": "Statements 1 and 3 are compliant with GDPR requirements"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseComplianceQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Evaluate the following statements for GDPR compliance", question.getQuestionText());
        assertEquals(QuestionType.COMPLIANCE, question.getType());
        assertEquals(Difficulty.MEDIUM, question.getDifficulty());
        assertEquals("Consider GDPR principles of consent, purpose limitation, and user rights", question.getHint());
        assertEquals("Statements 1 and 3 are compliant with GDPR requirements", question.getExplanation());
        
        // Verify content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("statements"));
        assertEquals(4, content.get("statements").size());
        
        // Verify statements
        JsonNode statement1 = content.get("statements").get(0);
        assertEquals(1, statement1.get("id").asInt());
        assertEquals("Personal data is processed with explicit consent", statement1.get("text").asText());
        assertTrue(statement1.get("compliant").asBoolean());
        
        JsonNode statement2 = content.get("statements").get(1);
        assertEquals(2, statement2.get("id").asInt());
        assertEquals("Data is stored indefinitely without purpose", statement2.get("text").asText());
        assertFalse(statement2.get("compliant").asBoolean());
    }

    @Test
    void shouldParseMultipleComplianceQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate security compliance statements",
                  "difficulty": "EASY",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Passwords are encrypted", "compliant": true},
                      {"id": 2, "text": "Passwords are stored in plain text", "compliant": false}
                    ]
                  }
                },
                {
                  "questionText": "Assess data protection compliance",
                  "difficulty": "HARD",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Data minimization principle is followed", "compliant": true},
                      {"id": 2, "text": "Cross-border data transfers are properly documented", "compliant": true},
                      {"id": 3, "text": "Data retention policies are not enforced", "compliant": false},
                      {"id": 4, "text": "Privacy impact assessments are conducted", "compliant": true},
                      {"id": 5, "text": "Data subjects are not informed of their rights", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseComplianceQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());
        
        // First question
        Question question1 = questions.get(0);
        assertEquals("Evaluate security compliance statements", question1.getQuestionText());
        assertEquals(Difficulty.EASY, question1.getDifficulty());
        
        JsonNode content1 = objectMapper.readTree(question1.getContent());
        assertEquals(2, content1.get("statements").size());
        
        // Second question
        Question question2 = questions.get(1);
        assertEquals("Assess data protection compliance", question2.getQuestionText());
        assertEquals(Difficulty.HARD, question2.getDifficulty());
        
        JsonNode content2 = objectMapper.readTree(question2.getContent());
        assertEquals(5, content2.get("statements").size());
    }

    @Test
    void shouldHandleMissingDifficulty() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Compliant statement", "compliant": true},
                      {"id": 2, "text": "Non-compliant statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseComplianceQuestions(contentNode);

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
                  "questionText": "Evaluate compliance statements",
                  "difficulty": "INVALID_DIFFICULTY",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Compliant statement", "compliant": true},
                      {"id": 2, "text": "Non-compliant statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseComplianceQuestions(contentNode);

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
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE"
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingStatements() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {}
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyStatements() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": []
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForSingleStatement() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Only one statement", "compliant": true}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForTooManyStatements() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Statement 1", "compliant": true},
                      {"id": 2, "text": "Statement 2", "compliant": false},
                      {"id": 3, "text": "Statement 3", "compliant": true},
                      {"id": 4, "text": "Statement 4", "compliant": false},
                      {"id": 5, "text": "Statement 5", "compliant": true},
                      {"id": 6, "text": "Statement 6", "compliant": false},
                      {"id": 7, "text": "Statement 7", "compliant": true}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingStatementId() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"text": "Statement without ID", "compliant": true},
                      {"id": 2, "text": "Valid statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonIntegerStatementId() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": "one", "text": "Statement with non-integer ID", "compliant": true},
                      {"id": 2, "text": "Valid statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingStatementText() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "compliant": true},
                      {"id": 2, "text": "Valid statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyStatementText() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "", "compliant": true},
                      {"id": 2, "text": "Valid statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingCompliantFlag() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Statement without compliant flag"},
                      {"id": 2, "text": "Valid statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonBooleanCompliantFlag() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Statement with non-boolean compliant", "compliant": "yes"},
                      {"id": 2, "text": "Valid statement", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForDuplicateStatementIds() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "First statement", "compliant": true},
                      {"id": 1, "text": "Second statement with same ID", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNoCompliantStatements() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Evaluate compliance statements",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Non-compliant statement 1", "compliant": false},
                      {"id": 2, "text": "Non-compliant statement 2", "compliant": false}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseComplianceQuestions(contentNode);
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
        List<Question> questions = parser.parseComplianceQuestions(contentNode);

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
        List<Question> questions = parser.parseComplianceQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }
} 