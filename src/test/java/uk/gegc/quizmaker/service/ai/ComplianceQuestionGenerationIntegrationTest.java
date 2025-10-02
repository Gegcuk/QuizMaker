package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.ai.infra.parser.ComplianceQuestionParser;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
class ComplianceQuestionGenerationIntegrationTest {

    @Autowired
    private ComplianceQuestionParser complianceQuestionParser;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldGenerateComplianceQuestionsFromContent() throws Exception {
        // Given - Sample content that would be used for COMPLIANCE questions
        String sampleContent = """
            GDPR Compliance Requirements:
            - Personal data must be processed with explicit consent
            - Data subjects have the right to request deletion
            - Data must be stored only for the specified purpose
            - Cross-border transfers require adequate safeguards
            - Privacy impact assessments are mandatory for high-risk processing
            
            Security Standards:
            - Passwords must be encrypted and hashed
            - Multi-factor authentication should be implemented
            - Regular security audits are required
            - Incident response procedures must be documented
            """;

        // When - Simulate AI response for COMPLIANCE questions
        String aiResponse = """
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
                  "explanation": "Statements 1 and 3 are compliant with GDPR requirements for consent and data subject rights"
                },
                {
                  "questionText": "Assess security compliance statements",
                  "difficulty": "EASY",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Passwords are encrypted and hashed", "compliant": true},
                      {"id": 2, "text": "Passwords are stored in plain text", "compliant": false}
                    ]
                  },
                  "hint": "Think about basic security best practices",
                  "explanation": "Statement 1 follows security best practices while statement 2 violates basic security principles"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = complianceQuestionParser.parseComplianceQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());

        // Verify first question (GDPR compliance)
        Question gdprQuestion = questions.get(0);
        assertEquals("Evaluate the following statements for GDPR compliance", 
                     gdprQuestion.getQuestionText());
        assertEquals(QuestionType.COMPLIANCE, gdprQuestion.getType());
        assertEquals(Difficulty.MEDIUM, gdprQuestion.getDifficulty());
        assertEquals("Consider GDPR principles of consent, purpose limitation, and user rights", gdprQuestion.getHint());
        assertEquals("Statements 1 and 3 are compliant with GDPR requirements for consent and data subject rights", 
                     gdprQuestion.getExplanation());

        // Verify content structure for first question
        JsonNode content1 = objectMapper.readTree(gdprQuestion.getContent());
        assertTrue(content1.has("statements"));
        assertEquals(4, content1.get("statements").size());
        
        // Verify statements are correctly marked
        JsonNode statement1 = content1.get("statements").get(0);
        assertEquals(1, statement1.get("id").asInt());
        assertEquals("Personal data is processed with explicit consent", statement1.get("text").asText());
        assertTrue(statement1.get("compliant").asBoolean());
        
        JsonNode statement2 = content1.get("statements").get(1);
        assertEquals(2, statement2.get("id").asInt());
        assertEquals("Data is stored indefinitely without purpose", statement2.get("text").asText());
        assertFalse(statement2.get("compliant").asBoolean());

        // Verify second question (Security compliance)
        Question securityQuestion = questions.get(1);
        assertEquals("Assess security compliance statements", securityQuestion.getQuestionText());
        assertEquals(QuestionType.COMPLIANCE, securityQuestion.getType());
        assertEquals(Difficulty.EASY, securityQuestion.getDifficulty());
        assertEquals("Think about basic security best practices", securityQuestion.getHint());
        assertEquals("Statement 1 follows security best practices while statement 2 violates basic security principles", 
                     securityQuestion.getExplanation());

        // Verify content structure for second question
        JsonNode content2 = objectMapper.readTree(securityQuestion.getContent());
        assertTrue(content2.has("statements"));
        assertEquals(2, content2.get("statements").size());
        
        // Verify statements are correctly marked
        JsonNode secStatement1 = content2.get("statements").get(0);
        assertEquals(1, secStatement1.get("id").asInt());
        assertEquals("Passwords are encrypted and hashed", secStatement1.get("text").asText());
        assertTrue(secStatement1.get("compliant").asBoolean());
        
        JsonNode secStatement2 = content2.get("statements").get(1);
        assertEquals(2, secStatement2.get("id").asInt());
        assertEquals("Passwords are stored in plain text", secStatement2.get("text").asText());
        assertFalse(secStatement2.get("compliant").asBoolean());
    }

    @Test
    void shouldHandleComplexComplianceScenarios() throws Exception {
        // Given - Complex compliance scenario with multiple criteria
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Evaluate data protection compliance across multiple regulations",
                  "difficulty": "HARD",
                  "type": "COMPLIANCE",
                  "content": {
                    "statements": [
                      {"id": 1, "text": "Data minimization principle is followed", "compliant": true},
                      {"id": 2, "text": "Cross-border data transfers are properly documented", "compliant": true},
                      {"id": 3, "text": "Data retention policies are not enforced", "compliant": false},
                      {"id": 4, "text": "Privacy impact assessments are conducted", "compliant": true},
                      {"id": 5, "text": "Data subjects are not informed of their rights", "compliant": false},
                      {"id": 6, "text": "Technical and organizational measures are implemented", "compliant": true}
                    ]
                  },
                  "hint": "Consider requirements from GDPR, CCPA, and industry standards",
                  "explanation": "Statements 1, 2, 4, and 6 demonstrate compliance with data protection regulations"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = complianceQuestionParser.parseComplianceQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Evaluate data protection compliance across multiple regulations", 
                     question.getQuestionText());
        assertEquals(QuestionType.COMPLIANCE, question.getType());
        assertEquals(Difficulty.HARD, question.getDifficulty());

        // Verify complex content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("statements"));
        assertEquals(6, content.get("statements").size());

        // Verify all statements have unique IDs and proper structure
        for (int i = 0; i < 6; i++) {
            JsonNode statement = content.get("statements").get(i);
            assertEquals(i + 1, statement.get("id").asInt());
            assertFalse(statement.get("text").asText().trim().isEmpty());
            assertTrue(statement.has("compliant"));
            assertTrue(statement.get("compliant").isBoolean());
        }

        // Verify at least one statement is compliant
        boolean hasCompliant = false;
        for (int i = 0; i < 6; i++) {
            JsonNode statement = content.get("statements").get(i);
            if (statement.get("compliant").asBoolean()) {
                hasCompliant = true;
                break;
            }
        }
        assertTrue(hasCompliant, "At least one statement should be compliant");
    }

    @Test
    void shouldValidateComplianceQuestionStructure() {
        // Given - Invalid structure with missing required fields
        String invalidResponse = """
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
        assertThrows(Exception.class, () -> {
            JsonNode contentNode = objectMapper.readTree(invalidResponse);
            complianceQuestionParser.parseComplianceQuestions(contentNode);
        });
    }

    @Test
    void shouldHandleEdgeCasesGracefully() throws Exception {
        // Given - Edge case with minimum valid statements
        String edgeCaseResponse = """
            {
              "questions": [
                {
                  "questionText": "Evaluate basic compliance",
                  "difficulty": "EASY",
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

        JsonNode contentNode = objectMapper.readTree(edgeCaseResponse);

        // When
        List<Question> questions = complianceQuestionParser.parseComplianceQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(QuestionType.COMPLIANCE, question.getType());
        assertEquals(Difficulty.EASY, question.getDifficulty());

        JsonNode content = objectMapper.readTree(question.getContent());
        assertEquals(2, content.get("statements").size());
        
        // Verify one statement is compliant and one is not
        boolean hasCompliant = false;
        boolean hasNonCompliant = false;
        for (int i = 0; i < 2; i++) {
            JsonNode statement = content.get("statements").get(i);
            if (statement.get("compliant").asBoolean()) {
                hasCompliant = true;
            } else {
                hasNonCompliant = true;
            }
        }
        assertTrue(hasCompliant, "Should have at least one compliant statement");
        assertTrue(hasNonCompliant, "Should have at least one non-compliant statement");
    }
} 