package uk.gegc.quizmaker.features.ai.infra.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema drift guard tests - prevents regression to old schema shapes.
 * These tests ensure that fixed schema issues don't reappear.
 */
@DisplayName("Question Schema Registry - Drift Guards")
class QuestionSchemaRegistryDriftGuardsTest {
    
    private QuestionSchemaRegistry schemaRegistry;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
    }
    
    @Test
    @DisplayName("TRUE_FALSE schema should NOT contain 'correctAnswer' field (old name)")
    void trueFalseSchemaShouldNotContainOldCorrectAnswerField() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        JsonNode contentProps = extractContentSchema(schema);
        
        // Then - verify old field name is NOT present
        assertThat(contentProps.has("correctAnswer"))
                .as("TRUE_FALSE schema should not have 'correctAnswer' (old name)")
                .isFalse();
        
        // And new field name IS present
        assertThat(contentProps.has("answer"))
                .as("TRUE_FALSE schema must have 'answer' field")
                .isTrue();
        assertThat(contentProps.get("answer").get("type").asText())
                .isEqualTo("boolean");
    }
    
    @Test
    @DisplayName("FILL_GAP schema should NOT contain old field names (textWithGaps, correctAnswers)")
    void fillGapSchemaShouldNotContainOldFields() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.FILL_GAP);
        JsonNode contentProps = extractContentSchema(schema);
        
        // Then - verify old field names are NOT present
        assertThat(contentProps.has("textWithGaps"))
                .as("FILL_GAP schema should not have 'textWithGaps' (old name)")
                .isFalse();
        assertThat(contentProps.has("correctAnswers"))
                .as("FILL_GAP schema should not have 'correctAnswers' (old name)")
                .isFalse();
        
        // And new field names ARE present
        assertThat(contentProps.has("text"))
                .as("FILL_GAP schema must have 'text' field")
                .isTrue();
        assertThat(contentProps.has("gaps"))
                .as("FILL_GAP schema must have 'gaps' field")
                .isTrue();
        
        // And gaps is an array of objects with id and answer
        JsonNode gaps = contentProps.get("gaps");
        assertThat(gaps.get("type").asText()).isEqualTo("array");
        JsonNode gapItem = gaps.get("items");
        JsonNode gapProps = gapItem.get("properties");
        assertThat(gapProps.has("id")).isTrue();
        assertThat(gapProps.has("answer")).isTrue();
    }
    
    @Test
    @DisplayName("ORDERING schema should have object items with id/text, not string array")
    void orderingSchemaShouldHaveObjectItemsNotStrings() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.ORDERING);
        JsonNode contentProps = extractContentSchema(schema);
        JsonNode items = contentProps.get("items");
        JsonNode itemSchema = items.get("items");
        
        // Then - items should be objects, not strings
        assertThat(itemSchema.get("type").asText())
                .as("ORDERING items should be objects, not strings")
                .isEqualTo("object");
        
        assertThat(itemSchema.has("properties"))
                .as("ORDERING item schema should have properties")
                .isTrue();
        
        JsonNode itemProps = itemSchema.get("properties");
        assertThat(itemProps.has("id"))
                .as("ORDERING items must have 'id' field")
                .isTrue();
        assertThat(itemProps.has("text"))
                .as("ORDERING items must have 'text' field")
                .isTrue();
        
        // Verify id is integer, text is string
        assertThat(itemProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(itemProps.get("text").get("type").asText()).isEqualTo("string");
    }
    
    @Test
    @DisplayName("HOTSPOT schema should use 'regions' not 'hotspots' (old name)")
    void hotspotSchemaShouldUseRegionsNotHotspots() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.HOTSPOT);
        JsonNode contentProps = extractContentSchema(schema);
        
        // Then - verify old field name is NOT present
        assertThat(contentProps.has("hotspots"))
                .as("HOTSPOT schema should not have 'hotspots' (old name)")
                .isFalse();
        
        // And new field name IS present
        assertThat(contentProps.has("regions"))
                .as("HOTSPOT schema must have 'regions' field")
                .isTrue();
        
        // And regions have all required coordinate fields
        JsonNode regions = contentProps.get("regions");
        assertThat(regions.get("type").asText()).isEqualTo("array");
        JsonNode regionItem = regions.get("items");
        JsonNode regionProps = regionItem.get("properties");
        
        assertThat(regionProps.has("id")).isTrue();
        assertThat(regionProps.has("x")).isTrue();
        assertThat(regionProps.has("y")).isTrue();
        assertThat(regionProps.has("width")).isTrue();
        assertThat(regionProps.has("height")).isTrue();
        assertThat(regionProps.has("correct")).isTrue();
    }
    
    @Test
    @DisplayName("HOTSPOT regions should require width and height (not old simple x/y)")
    void hotspotRegionsShouldHaveWidthAndHeight() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.HOTSPOT);
        JsonNode contentProps = extractContentSchema(schema);
        JsonNode regions = contentProps.get("regions");
        JsonNode regionItem = regions.get("items");
        JsonNode regionProps = regionItem.get("properties");
        
        // Then - verify regions have width and height (not just points)
        assertThat(regionProps.has("width"))
                .as("HOTSPOT regions must have 'width' field")
                .isTrue();
        assertThat(regionProps.has("height"))
                .as("HOTSPOT regions must have 'height' field")
                .isTrue();
        
        // And all coordinates are integers
        assertThat(regionProps.get("x").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.get("y").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.get("width").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.get("height").get("type").asText()).isEqualTo("integer");
    }
    
    @Test
    @DisplayName("COMPLIANCE schema should use 'statements' not 'scenario/correctAction' (old structure)")
    void complianceSchemaShouldUseStatementsNotScenario() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.COMPLIANCE);
        JsonNode contentProps = extractContentSchema(schema);
        
        // Then - verify old field names are NOT present
        assertThat(contentProps.has("scenario"))
                .as("COMPLIANCE schema should not have 'scenario' (old name)")
                .isFalse();
        assertThat(contentProps.has("correctAction"))
                .as("COMPLIANCE schema should not have 'correctAction' (old name)")
                .isFalse();
        
        // And new field name IS present
        assertThat(contentProps.has("statements"))
                .as("COMPLIANCE schema must have 'statements' field")
                .isTrue();
        
        // And statements is an array of objects with id, text, compliant
        JsonNode statements = contentProps.get("statements");
        assertThat(statements.get("type").asText()).isEqualTo("array");
        JsonNode statementItem = statements.get("items");
        JsonNode statementProps = statementItem.get("properties");
        
        assertThat(statementProps.has("id")).isTrue();
        assertThat(statementProps.has("text")).isTrue();
        assertThat(statementProps.has("compliant")).isTrue();
    }
    
    @Test
    @DisplayName("FILL_GAP gaps should be array of objects with id/answer, not string array")
    void fillGapGapsShouldBeObjectsWithIdAndAnswer() {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.FILL_GAP);
        JsonNode contentProps = extractContentSchema(schema);
        JsonNode gaps = contentProps.get("gaps");
        
        // Then - gaps should be array of objects
        assertThat(gaps.get("type").asText()).isEqualTo("array");
        JsonNode gapItem = gaps.get("items");
        assertThat(gapItem.get("type").asText())
                .as("FILL_GAP gaps items should be objects, not strings")
                .isEqualTo("object");
        
        // With required id and answer fields
        JsonNode required = gapItem.get("required");
        assertThat(required).isNotNull();
        assertThat(required.toString()).contains("id", "answer");
        
        // And correct types
        JsonNode gapProps = gapItem.get("properties");
        assertThat(gapProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(gapProps.get("answer").get("type").asText()).isEqualTo("string");
    }
    
    /**
     * Helper to extract content schema from question items
     */
    private JsonNode extractContentSchema(JsonNode fullSchema) {
        return fullSchema
                .get("properties")
                .get("questions")
                .get("items")
                .get("properties")
                .get("content")
                .get("properties");
    }
}

