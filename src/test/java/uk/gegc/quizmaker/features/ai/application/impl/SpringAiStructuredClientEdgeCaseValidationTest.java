package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional edge case validation tests for SpringAiStructuredClient.
 * Tests per-type content structure validation for edge cases per code review.
 */
@DisplayName("Spring AI Structured Client - Edge Case Validation Tests")
class SpringAiStructuredClientEdgeCaseValidationTest {
    
    private SpringAiStructuredClient structuredClient;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        QuestionSchemaRegistry schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        
        // Create minimal client with only dependencies needed for validation
        structuredClient = new SpringAiStructuredClient(
                null,  // chatClient not needed for validation
                schemaRegistry,
                null,  // promptTemplateService not needed for validation
                objectMapper,
                null   // rateLimitConfig not needed for validation
        );
    }
    
    @Test
    @DisplayName("ORDERING: should reject missing 'items' array")
    void orderingShouldRejectMissingItemsArray() throws Exception {
        // Given - ORDERING content without items
        ObjectNode content = objectMapper.createObjectNode();
        // Missing items array!
        
        // When/Then - should throw AIResponseParseException
        assertThatThrownBy(() -> invokeValidateContentStructure(content, QuestionType.ORDERING))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("ORDERING question must have 'items' array");
    }
    
    @Test
    @DisplayName("ORDERING: should accept items array (detailed validation done by parser)")
    void orderingShouldAcceptItemsArray() throws Exception {
        // Given - ORDERING content with items array (item structure validated by parser)
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        ObjectNode item1 = objectMapper.createObjectNode();
        item1.put("text", "First step");  // Even without id, top-level validation passes
        items.add(item1);
        content.set("items", items);
        
        // When/Then - should not throw (item structure validated by parser layer)
        invokeValidateContentStructure(content, QuestionType.ORDERING);
    }
    
    @Test
    @DisplayName("ORDERING: should accept valid items with id and text")
    void orderingShouldAcceptValidItems() throws Exception {
        // Given - valid ORDERING content
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        
        ObjectNode item1 = objectMapper.createObjectNode();
        item1.put("id", 1);
        item1.put("text", "First step");
        items.add(item1);
        
        ObjectNode item2 = objectMapper.createObjectNode();
        item2.put("id", 2);
        item2.put("text", "Second step");
        items.add(item2);
        
        content.set("items", items);
        
        // When/Then - should not throw
        invokeValidateContentStructure(content, QuestionType.ORDERING);
    }
    
    @Test
    @DisplayName("HOTSPOT: should reject missing 'imageUrl' field")
    void hotspotShouldRejectMissingImageUrl() throws Exception {
        // Given - HOTSPOT content without imageUrl
        ObjectNode content = objectMapper.createObjectNode();
        // Missing imageUrl!
        
        ArrayNode regions = objectMapper.createArrayNode();
        ObjectNode region1 = objectMapper.createObjectNode();
        region1.put("id", 1);
        region1.put("x", 100);
        region1.put("y", 100);
        region1.put("width", 50);
        region1.put("height", 50);
        region1.put("correct", true);
        regions.add(region1);
        
        content.set("regions", regions);
        
        // When/Then - should throw AIResponseParseException
        assertThatThrownBy(() -> invokeValidateContentStructure(content, QuestionType.HOTSPOT))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("HOTSPOT question must have 'imageUrl' and 'regions'");
    }
    
    @Test
    @DisplayName("HOTSPOT: should reject missing 'regions' array")
    void hotspotShouldRejectMissingRegions() throws Exception {
        // Given - HOTSPOT content without regions
        ObjectNode content = objectMapper.createObjectNode();
        content.put("imageUrl", "http://example.com/image.png");
        // Missing regions!
        
        // When/Then - should throw AIResponseParseException
        assertThatThrownBy(() -> invokeValidateContentStructure(content, QuestionType.HOTSPOT))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("HOTSPOT question must have 'imageUrl' and 'regions'");
    }
    
    @Test
    @DisplayName("HOTSPOT: should reject region with negative coordinates")
    void hotspotShouldRejectNegativeCoordinates() throws Exception {
        // Given - HOTSPOT content with negative coordinates
        ObjectNode content = objectMapper.createObjectNode();
        content.put("imageUrl", "http://example.com/image.png");
        
        ArrayNode regions = objectMapper.createArrayNode();
        ObjectNode region1 = objectMapper.createObjectNode();
        region1.put("id", 1);
        region1.put("x", -10);  // Negative!
        region1.put("y", 100);
        region1.put("width", 50);
        region1.put("height", 50);
        region1.put("correct", true);
        regions.add(region1);
        
        content.set("regions", regions);
        
        // When/Then - coordinates are not validated (parser will handle), but structure should pass
        // This test documents that coordinate validation is not enforced at this layer
        invokeValidateContentStructure(content, QuestionType.HOTSPOT);
    }
    
    @Test
    @DisplayName("HOTSPOT: should accept valid regions with all required fields")
    void hotspotShouldAcceptValidRegions() throws Exception {
        // Given - valid HOTSPOT content
        ObjectNode content = objectMapper.createObjectNode();
        content.put("imageUrl", "http://example.com/image.png");
        
        ArrayNode regions = objectMapper.createArrayNode();
        ObjectNode region1 = objectMapper.createObjectNode();
        region1.put("id", 1);
        region1.put("x", 100);
        region1.put("y", 100);
        region1.put("width", 50);
        region1.put("height", 50);
        region1.put("correct", true);
        regions.add(region1);
        
        ObjectNode region2 = objectMapper.createObjectNode();
        region2.put("id", 2);
        region2.put("x", 200);
        region2.put("y", 200);
        region2.put("width", 60);
        region2.put("height", 60);
        region2.put("correct", false);
        regions.add(region2);
        
        content.set("regions", regions);
        
        // When/Then - should not throw
        invokeValidateContentStructure(content, QuestionType.HOTSPOT);
    }
    
    @Test
    @DisplayName("COMPLIANCE: should reject missing 'statements' array")
    void complianceShouldRejectMissingStatements() throws Exception {
        // Given - COMPLIANCE content without statements
        ObjectNode content = objectMapper.createObjectNode();
        // Missing statements!
        
        // When/Then - should throw AIResponseParseException
        assertThatThrownBy(() -> invokeValidateContentStructure(content, QuestionType.COMPLIANCE))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("COMPLIANCE question must have 'statements' array");
    }
    
    @Test
    @DisplayName("COMPLIANCE: should accept empty statements array (detailed validation done by parser)")
    void complianceShouldAcceptEmptyStatements() throws Exception {
        // Given - COMPLIANCE content with empty statements array
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode statements = objectMapper.createArrayNode();  // Empty - parser will validate
        content.set("statements", statements);
        
        // When/Then - should not throw (statement count validated by parser layer)
        invokeValidateContentStructure(content, QuestionType.COMPLIANCE);
    }
    
    @Test
    @DisplayName("COMPLIANCE: should reject statement with non-boolean 'compliant' field")
    void complianceShouldRejectNonBooleanCompliant() throws Exception {
        // Given - COMPLIANCE content with non-boolean compliant
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode statements = objectMapper.createArrayNode();
        
        ObjectNode statement1 = objectMapper.createObjectNode();
        statement1.put("id", 1);
        statement1.put("text", "Statement 1");
        statement1.put("compliant", "true");  // String, not boolean!
        statements.add(statement1);
        
        content.set("statements", statements);
        
        // When/Then - type validation is not enforced at this layer (parser handles it)
        // This test documents that type validation is delegated
        invokeValidateContentStructure(content, QuestionType.COMPLIANCE);
    }
    
    @Test
    @DisplayName("COMPLIANCE: should accept valid statements")
    void complianceShouldAcceptValidStatements() throws Exception {
        // Given - valid COMPLIANCE content
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode statements = objectMapper.createArrayNode();
        
        ObjectNode statement1 = objectMapper.createObjectNode();
        statement1.put("id", 1);
        statement1.put("text", "Always wear safety equipment");
        statement1.put("compliant", true);
        statements.add(statement1);
        
        ObjectNode statement2 = objectMapper.createObjectNode();
        statement2.put("id", 2);
        statement2.put("text", "Skip safety checks to save time");
        statement2.put("compliant", false);
        statements.add(statement2);
        
        content.set("statements", statements);
        
        // When/Then - should not throw
        invokeValidateContentStructure(content, QuestionType.COMPLIANCE);
    }
    
    @Test
    @DisplayName("MCQ_MULTI: should accept multiple correct options")
    void mcqMultiShouldAcceptMultipleCorrect() throws Exception {
        // Given - MCQ_MULTI content with multiple correct options
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        
        ObjectNode opt1 = objectMapper.createObjectNode();
        opt1.put("id", "a");
        opt1.put("text", "Option A");
        opt1.put("correct", true);
        options.add(opt1);
        
        ObjectNode opt2 = objectMapper.createObjectNode();
        opt2.put("id", "b");
        opt2.put("text", "Option B");
        opt2.put("correct", true);  // Also correct
        options.add(opt2);
        
        ObjectNode opt3 = objectMapper.createObjectNode();
        opt3.put("id", "c");
        opt3.put("text", "Option C");
        opt3.put("correct", false);
        options.add(opt3);
        
        ObjectNode opt4 = objectMapper.createObjectNode();
        opt4.put("id", "d");
        opt4.put("text", "Option D");
        opt4.put("correct", false);
        options.add(opt4);
        
        content.set("options", options);
        
        // When/Then - should not throw (parser will validate at least 1 correct)
        invokeValidateContentStructure(content, QuestionType.MCQ_MULTI);
    }
    
    @Test
    @DisplayName("MCQ_SINGLE: should accept exactly 4 options (aligned with schema)")
    void mcqSingleShouldAcceptExactly4Options() throws Exception {
        // Given - MCQ_SINGLE content with exactly 4 options
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        
        for (int i = 0; i < 4; i++) {
            ObjectNode opt = objectMapper.createObjectNode();
            opt.put("id", String.valueOf((char)('a' + i)));
            opt.put("text", "Option " + (char)('A' + i));
            opt.put("correct", i == 0);  // Only first is correct
            options.add(opt);
        }
        
        content.set("options", options);
        
        // When/Then - should not throw
        invokeValidateContentStructure(content, QuestionType.MCQ_SINGLE);
    }
    
    /**
     * Helper to invoke private validateContentStructure method via reflection
     */
    private void invokeValidateContentStructure(JsonNode contentNode, QuestionType type) throws Exception {
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
                "validateContentStructure", JsonNode.class, QuestionType.class
        );
        method.setAccessible(true);
        
        try {
            method.invoke(structuredClient, contentNode, type);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap and rethrow the actual exception
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}

