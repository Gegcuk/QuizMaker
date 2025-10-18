package uk.gegc.quizmaker.features.ai.infra.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for QuestionSchemaRegistry.
 * Verifies JSON schema generation aligns with parser expectations.
 */
class QuestionSchemaRegistryTest {
    
    private QuestionSchemaRegistry schemaRegistry;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
    }
    
    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void shouldGenerateValidSchemaForAllQuestionTypes(QuestionType questionType) {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(questionType);
        
        // Then
        assertThat(schema).isNotNull();
        assertThat(schema.has("$schema")).isTrue();
        assertThat(schema.get("$schema").asText()).isEqualTo("http://json-schema.org/draft-07/schema#");
        assertThat(schema.has("type")).isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.has("properties")).isTrue();
        assertThat(schema.has("required")).isTrue();
        
        JsonNode properties = schema.get("properties");
        assertThat(properties.has("questions")).isTrue();
        
        JsonNode questions = properties.get("questions");
        assertThat(questions.get("type").asText()).isEqualTo("array");
        assertThat(questions.has("items")).isTrue();
    }
    
    @Test
    void shouldGenerateTrueFalseSchemaWithAnswerField() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        assertThat(content.has("answer")).isTrue();
        assertThat(content.get("answer").get("type").asText()).isEqualTo("boolean");
    }
    
    @Test
    void shouldGenerateFillGapSchemaWithTextAndGaps() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.FILL_GAP);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        
        // Verify text field
        assertThat(content.has("text")).isTrue();
        assertThat(content.get("text").get("type").asText()).isEqualTo("string");
        
        // Verify gaps array
        assertThat(content.has("gaps")).isTrue();
        JsonNode gaps = content.get("gaps");
        assertThat(gaps.get("type").asText()).isEqualTo("array");
        
        // Verify gap item structure
        JsonNode gapItem = gaps.get("items");
        JsonNode gapProps = gapItem.get("properties");
        assertThat(gapProps.has("id")).isTrue();
        assertThat(gapProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(gapProps.has("answer")).isTrue();
        assertThat(gapProps.get("answer").get("type").asText()).isEqualTo("string");
    }
    
    @Test
    void shouldGenerateOrderingSchemaWithItemsArray() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.ORDERING);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        
        // Verify items array
        assertThat(content.has("items")).isTrue();
        JsonNode items = content.get("items");
        assertThat(items.get("type").asText()).isEqualTo("array");
        assertThat(items.get("minItems").asInt()).isEqualTo(3); // Changed to 3 for better quality
        assertThat(items.get("maxItems").asInt()).isEqualTo(10);
        
        // Verify item structure
        JsonNode itemSchema = items.get("items");
        JsonNode itemProps = itemSchema.get("properties");
        assertThat(itemProps.has("id")).isTrue();
        assertThat(itemProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(itemProps.has("text")).isTrue();
        assertThat(itemProps.get("text").get("type").asText()).isEqualTo("string");
    }
    
    @Test
    void shouldGenerateHotspotSchemaWithRegions() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.HOTSPOT);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        
        // Verify imageUrl
        assertThat(content.has("imageUrl")).isTrue();
        assertThat(content.get("imageUrl").get("type").asText()).isEqualTo("string");
        
        // Verify regions array
        assertThat(content.has("regions")).isTrue();
        JsonNode regions = content.get("regions");
        assertThat(regions.get("type").asText()).isEqualTo("array");
        assertThat(regions.get("minItems").asInt()).isEqualTo(2);
        assertThat(regions.get("maxItems").asInt()).isEqualTo(6);
        
        // Verify region structure
        JsonNode regionItem = regions.get("items");
        JsonNode regionProps = regionItem.get("properties");
        assertThat(regionProps.has("id")).isTrue();
        assertThat(regionProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.has("x")).isTrue();
        assertThat(regionProps.get("x").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.has("y")).isTrue();
        assertThat(regionProps.get("y").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.has("width")).isTrue();
        assertThat(regionProps.get("width").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.has("height")).isTrue();
        assertThat(regionProps.get("height").get("type").asText()).isEqualTo("integer");
        assertThat(regionProps.has("correct")).isTrue();
        assertThat(regionProps.get("correct").get("type").asText()).isEqualTo("boolean");
    }
    
    @Test
    void shouldGenerateComplianceSchemaWithStatements() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.COMPLIANCE);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        
        // Verify statements array
        assertThat(content.has("statements")).isTrue();
        JsonNode statements = content.get("statements");
        assertThat(statements.get("type").asText()).isEqualTo("array");
        assertThat(statements.get("minItems").asInt()).isEqualTo(2);
        assertThat(statements.get("maxItems").asInt()).isEqualTo(6);
        
        // Verify statement structure
        JsonNode statementItem = statements.get("items");
        JsonNode statementProps = statementItem.get("properties");
        assertThat(statementProps.has("id")).isTrue();
        assertThat(statementProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(statementProps.has("text")).isTrue();
        assertThat(statementProps.get("text").get("type").asText()).isEqualTo("string");
        assertThat(statementProps.has("compliant")).isTrue();
        assertThat(statementProps.get("compliant").get("type").asText()).isEqualTo("boolean");
    }
    
    @Test
    void shouldGenerateMcqSingleSchemaWithOptions() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        
        // Verify options array (MCQ_SINGLE requires exactly 4 options)
        assertThat(content.has("options")).isTrue();
        JsonNode options = content.get("options");
        assertThat(options.get("type").asText()).isEqualTo("array");
        assertThat(options.get("minItems").asInt()).isEqualTo(4);  // Exactly 4 for MCQ_SINGLE
        assertThat(options.get("maxItems").asInt()).isEqualTo(4);  // Exactly 4 for MCQ_SINGLE
        
        // Verify option structure
        JsonNode optionItem = options.get("items");
        JsonNode optionProps = optionItem.get("properties");
        assertThat(optionProps.has("text")).isTrue();
        assertThat(optionProps.get("text").get("type").asText()).isEqualTo("string");
        assertThat(optionProps.has("correct")).isTrue();
        assertThat(optionProps.get("correct").get("type").asText()).isEqualTo("boolean");
    }
    
    @Test
    void shouldGenerateOpenQuestionSchemaWithAnswer() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.OPEN);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        assertThat(content.has("answer")).isTrue();
        assertThat(content.get("answer").get("type").asText()).isEqualTo("string");
    }
    
    @Test
    void shouldGenerateMatchingSchemaWithLeftAndRight() {
        // When
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MATCHING);
        
        // Then
        JsonNode content = extractContentSchema(schema);
        
        // Verify left array
        assertThat(content.has("left")).isTrue();
        JsonNode left = content.get("left");
        assertThat(left.get("type").asText()).isEqualTo("array");
        assertThat(left.get("minItems").asInt()).isEqualTo(4);
        
        // Verify left item structure (id, text, matchId)
        JsonNode leftItem = left.get("items");
        JsonNode leftProps = leftItem.get("properties");
        assertThat(leftProps.has("id")).isTrue();
        assertThat(leftProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(leftProps.has("text")).isTrue();
        assertThat(leftProps.get("text").get("type").asText()).isEqualTo("string");
        assertThat(leftProps.has("matchId")).isTrue();
        assertThat(leftProps.get("matchId").get("type").asText()).isEqualTo("integer");
        
        // Verify right array
        assertThat(content.has("right")).isTrue();
        JsonNode right = content.get("right");
        assertThat(right.get("type").asText()).isEqualTo("array");
        assertThat(right.get("minItems").asInt()).isEqualTo(4);
        
        // Verify right item structure (id, text)
        JsonNode rightItem = right.get("items");
        JsonNode rightProps = rightItem.get("properties");
        assertThat(rightProps.has("id")).isTrue();
        assertThat(rightProps.get("id").get("type").asText()).isEqualTo("integer");
        assertThat(rightProps.has("text")).isTrue();
        assertThat(rightProps.get("text").get("type").asText()).isEqualTo("string");
    }
    
    @Test
    void shouldGenerateCompositeSchema() {
        // When
        JsonNode schema = schemaRegistry.getCompositeSchema();
        
        // Then
        assertThat(schema).isNotNull();
        assertThat(schema.has("$schema")).isTrue();
        assertThat(schema.has("properties")).isTrue();
        
        JsonNode properties = schema.get("properties");
        assertThat(properties.has("questions")).isTrue();
        
        JsonNode questions = properties.get("questions");
        assertThat(questions.get("type").asText()).isEqualTo("array");
        assertThat(questions.has("items")).isTrue();
        
        // Verify question item has base properties
        JsonNode itemSchema = questions.get("items");
        JsonNode itemProps = itemSchema.get("properties");
        assertThat(itemProps.has("questionText")).isTrue();
        assertThat(itemProps.has("type")).isTrue();
        assertThat(itemProps.has("difficulty")).isTrue();
        assertThat(itemProps.has("content")).isTrue();
        assertThat(itemProps.has("hint")).isTrue();
        assertThat(itemProps.has("explanation")).isTrue();
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

