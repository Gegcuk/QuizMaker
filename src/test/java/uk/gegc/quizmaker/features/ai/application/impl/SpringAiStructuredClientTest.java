package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for  SpringAiStructuredClient schema generation.
 * 
 * Note: Full integration tests with mocked ChatClient are complex due to Spring AI's
 * fluent API. These unit tests focus on schema availability.
 * End-to-end behavior is tested via integration tests with real or stubbed API calls.
 */
class SpringAiStructuredClientTest {
    
    private QuestionSchemaRegistry schemaRegistry;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
    }
    
    @Test
    void shouldGenerateSchemasForAllQuestionTypes() {
        // When/Then - verify schemas exist for all types
        for (QuestionType type : QuestionType.values()) {
            assertThat(schemaRegistry.getSchemaForQuestionType(type)).isNotNull();
        }
    }
    
    @Test
    void shouldGenerateCompositeSchema() {
        // When
        var schema = schemaRegistry.getCompositeSchema();
        
        // Then
        assertThat(schema).isNotNull();
        assertThat(schema.has("$schema")).isTrue();
        assertThat(schema.get("$schema").asText()).contains("json-schema");
    }
}
