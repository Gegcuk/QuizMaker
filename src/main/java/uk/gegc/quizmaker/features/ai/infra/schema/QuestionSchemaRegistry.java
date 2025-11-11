package uk.gegc.quizmaker.features.ai.infra.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/**
 * Registry for JSON schemas used in structured output generation.
 * Provides type-specific schemas for LLM question generation with validation.
 * 
 * Phase 1 of structured output migration - centralizes schema definitions.
 * 
 * Design decision: Using dynamic schema generation rather than static files
 * for easier maintenance and type safety.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionSchemaRegistry {
    
    private final ObjectMapper objectMapper;
    
    /**
     * Get the JSON schema for a specific question type.
     * 
     * @param questionType The type of question to generate schema for
     * @return JSON schema as JsonNode
     */
    public JsonNode getSchemaForQuestionType(QuestionType questionType) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        
        // Add required properties
        ArrayNode required = objectMapper.createArrayNode();
        required.add("questions");
        schema.set("required", required);
        
        // Define properties
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("questions", createQuestionsArraySchema(questionType));
        schema.set("properties", properties);
        
        log.debug("Generated schema for question type: {}", questionType);
        return schema;
    }
    
    /**
     * Get a composite schema that supports multiple question types.
     * Uses oneOf to allow different content structures per type.
     * 
     * @return Composite JSON schema
     */
    public JsonNode getCompositeSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("questions");
        schema.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("questions", createQuestionsArraySchemaComposite());
        schema.set("properties", properties);
        
        log.debug("Generated composite schema for all question types");
        return schema;
    }
    
    /**
     * Create the questions array schema for a specific type
     */
    private ObjectNode createQuestionsArraySchema(QuestionType questionType) {
        ObjectNode arraySchema = objectMapper.createObjectNode();
        arraySchema.put("type", "array");
        arraySchema.set("items", createQuestionItemSchema(questionType));
        return arraySchema;
    }
    
    /**
     * Create the questions array schema supporting multiple types (composite)
     */
    private ObjectNode createQuestionsArraySchemaComposite() {
        ObjectNode arraySchema = objectMapper.createObjectNode();
        arraySchema.put("type", "array");
        
        // For composite, we allow any question type with oneOf
        ObjectNode itemSchema = objectMapper.createObjectNode();
        itemSchema.put("type", "object");
        itemSchema.put("additionalProperties", false);
        
        // Base required fields (all fields are required for strict mode)
        ArrayNode required = objectMapper.createArrayNode();
        required.add("questionText");
        required.add("type");
        required.add("difficulty");
        required.add("content");
        required.add("hint");
        required.add("explanation");
        required.add("confidence");
        itemSchema.set("required", required);
        
        // Base properties (common to all types)
        ObjectNode properties = createBaseQuestionProperties();
        itemSchema.set("properties", properties);
        
        arraySchema.set("items", itemSchema);
        return arraySchema;
    }
    
    /**
     * Create schema for a single question item of a specific type
     */
    private ObjectNode createQuestionItemSchema(QuestionType questionType) {
        ObjectNode itemSchema = objectMapper.createObjectNode();
        itemSchema.put("type", "object");
        itemSchema.put("additionalProperties", false);
        
        // Required fields (all fields are required for strict mode)
        ArrayNode required = objectMapper.createArrayNode();
        required.add("questionText");
        required.add("type");
        required.add("difficulty");
        required.add("content");
        required.add("hint");
        required.add("explanation");
        required.add("confidence");
        itemSchema.set("required", required);
        
        // Properties
        ObjectNode properties = createBaseQuestionProperties();
        
        // Add type-specific content schema
        properties.set("content", createContentSchemaForType(questionType));
        
        itemSchema.set("properties", properties);
        return itemSchema;
    }
    
    /**
     * Create base properties common to all question types
     */
    private ObjectNode createBaseQuestionProperties() {
        ObjectNode properties = objectMapper.createObjectNode();
        
        // questionText
        ObjectNode questionText = objectMapper.createObjectNode();
        questionText.put("type", "string");
        questionText.put("description", "The question text/stem");
        questionText.put("minLength", 10);
        questionText.put("maxLength", 1000);
        properties.set("questionText", questionText);
        
        // type
        ObjectNode type = objectMapper.createObjectNode();
        type.put("type", "string");
        type.put("description", "Question type");
        ArrayNode typeEnum = objectMapper.createArrayNode();
        for (QuestionType qt : QuestionType.values()) {
            typeEnum.add(qt.name());
        }
        type.set("enum", typeEnum);
        properties.set("type", type);
        
        // difficulty
        ObjectNode difficulty = objectMapper.createObjectNode();
        difficulty.put("type", "string");
        difficulty.put("description", "Difficulty level");
        ArrayNode difficultyEnum = objectMapper.createArrayNode();
        difficultyEnum.add("EASY");
        difficultyEnum.add("MEDIUM");
        difficultyEnum.add("HARD");
        difficulty.set("enum", difficultyEnum);
        properties.set("difficulty", difficulty);
        
        // hint (required for strict mode)
        ObjectNode hint = objectMapper.createObjectNode();
        hint.put("type", "string");
        hint.put("description", "Hint text to guide the user");
        hint.put("maxLength", 500);
        properties.set("hint", hint);
        
        // explanation (required for strict mode)
        ObjectNode explanation = objectMapper.createObjectNode();
        explanation.put("type", "string");
        explanation.put("description", "Explanation of the answer");
        explanation.put("maxLength", 2000);
        properties.set("explanation", explanation);
        
        // content (type-specific structure - generic for composite)
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        content.put("description", "Type-specific content structure");
        properties.set("content", content);
        
        // confidence (required for strict mode)
        ObjectNode confidence = objectMapper.createObjectNode();
        confidence.put("type", "number");
        confidence.put("description", "Confidence score 0.0-1.0");
        confidence.put("minimum", 0.0);
        confidence.put("maximum", 1.0);
        properties.set("confidence", confidence);
        
        return properties;
    }
    
    /**
     * Create type-specific content schema.
     * The content field structure varies by question type.
     */
    private ObjectNode createContentSchemaForType(QuestionType questionType) {
        return switch (questionType) {
            case MCQ_SINGLE, MCQ_MULTI -> createMcqContentSchema(questionType == QuestionType.MCQ_MULTI);
            case TRUE_FALSE -> createTrueFalseContentSchema();
            case OPEN -> createOpenContentSchema();
            case FILL_GAP -> createFillGapContentSchema();
            case ORDERING -> createOrderingContentSchema();
            case MATCHING -> createMatchingContentSchema();
            case HOTSPOT -> createHotspotContentSchema();
            case COMPLIANCE -> createComplianceContentSchema();
        };
    }
    
    /**
     * Schema for MCQ content (options with correctness)
     * Prompt template shows: {"id": "a", "text": "...", "correct": false}
     * Parser validates: text and correct (id is optional but recommended)
     * 
     * MCQ_SINGLE: Exactly 4 options (aligned with prompt template requirement)
     * MCQ_MULTI: 4-6 options (flexible for multiple correct answers)
     */
    private ObjectNode createMcqContentSchema(boolean multipleCorrect) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("options");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        // options array
        ObjectNode options = objectMapper.createObjectNode();
        options.put("type", "array");
        
        // MCQ_SINGLE: exactly 4 options to match prompt
        // MCQ_MULTI: 4-6 options for flexibility
        if (multipleCorrect) {
            options.put("minItems", 4);
            options.put("maxItems", 6);
            options.put("description", "Options for MCQ_MULTI (4-6 options, at least 1 correct)");
        } else {
            options.put("minItems", 4);
            options.put("maxItems", 4);
            options.put("description", "Options for MCQ_SINGLE (exactly 4 options, exactly 1 correct)");
        }
        
        ObjectNode optionItem = objectMapper.createObjectNode();
        optionItem.put("type", "object");
        optionItem.put("additionalProperties", false);
        
        ArrayNode optionRequired = objectMapper.createArrayNode();
        optionRequired.add("id");
        optionRequired.add("text");
        optionRequired.add("correct");
        optionItem.set("required", optionRequired);
        
        ObjectNode optionProps = objectMapper.createObjectNode();
        
        // id field (required for strict mode)
        ObjectNode optionId = objectMapper.createObjectNode();
        optionId.put("type", "string");
        optionId.put("description", "Identifier (a, b, c, d, etc.)");
        optionProps.set("id", optionId);
        
        ObjectNode optionText = objectMapper.createObjectNode();
        optionText.put("type", "string");
        optionText.put("description", "Option text");
        optionProps.set("text", optionText);
        
        ObjectNode optionCorrect = objectMapper.createObjectNode();
        optionCorrect.put("type", "boolean");
        optionCorrect.put("description", "Whether this option is correct");
        optionProps.set("correct", optionCorrect);
        
        optionItem.set("properties", optionProps);
        options.set("items", optionItem);
        
        properties.set("options", options);
        content.set("properties", properties);
        
        return content;
    }
    
    /**
     * Schema for TRUE/FALSE content
     * Parser expects: content.answer (boolean)
     */
    private ObjectNode createTrueFalseContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("answer");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode answer = objectMapper.createObjectNode();
        answer.put("type", "boolean");
        answer.put("description", "The correct answer (true or false)");
        properties.set("answer", answer);
        
        content.set("properties", properties);
        return content;
    }
    
    /**
     * Schema for OPEN question content
     */
    private ObjectNode createOpenContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("answer");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode answer = objectMapper.createObjectNode();
        answer.put("type", "string");
        answer.put("description", "Model answer for the open question");
        properties.set("answer", answer);
        
        content.set("properties", properties);
        return content;
    }
    
    /**
     * Schema for FILL_GAP content
     * Parser expects: content.text (string with {N} markers) and content.gaps [{id: int, answer: string}]
     * Example: {"text": "Java is a {1} language", "gaps": [{"id": 1, "answer": "programming"}]}
     */
    private ObjectNode createFillGapContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("text");
        required.add("gaps");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "string");
        text.put("description", "Text with gaps marked as {N} where N is the gap ID (e.g., {1}, {2}, {3}). Example: 'The capital of France is {1}'");
        properties.set("text", text);
        
        ObjectNode gaps = objectMapper.createObjectNode();
        gaps.put("type", "array");
        gaps.put("description", "Array of gaps with sequential IDs (1, 2, 3...) and correct answers. Each {N} in text must have a matching gap with id=N");
        gaps.put("minItems", 1);
        gaps.put("maxItems", 3);
        
        ObjectNode gapItem = objectMapper.createObjectNode();
        gapItem.put("type", "object");
        gapItem.put("additionalProperties", false);
        
        ArrayNode gapRequired = objectMapper.createArrayNode();
        gapRequired.add("id");
        gapRequired.add("answer");
        gapItem.set("required", gapRequired);
        
        ObjectNode gapProps = objectMapper.createObjectNode();
        
        ObjectNode gapId = objectMapper.createObjectNode();
        gapId.put("type", "integer");
        gapId.put("description", "Sequential gap ID starting from 1, matching {N} placeholder in text");
        gapId.put("minimum", 1);
        gapProps.set("id", gapId);
        
        ObjectNode gapAnswer = objectMapper.createObjectNode();
        gapAnswer.put("type", "string");
        gapAnswer.put("description", "Correct answer for this gap");
        gapAnswer.put("minLength", 1);
        gapProps.set("answer", gapAnswer);
        
        gapItem.set("properties", gapProps);
        gaps.set("items", gapItem);
        
        properties.set("gaps", gaps);
        content.set("properties", properties);
        return content;
    }
    
    /**
     * Schema for ORDERING content
     * Parser expects: content.items [{id: int, text: string}] with unique sequential IDs
     * CRITICAL: Items MUST be listed in the CORRECT order (1, 2, 3, 4, 5)
     */
    private ObjectNode createOrderingContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        content.put("description", "CRITICAL: List items in the CORRECT sequential order. Use IDs 1,2,3,4,5 to indicate the proper sequence. The system will shuffle for display.");
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("items");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode items = objectMapper.createObjectNode();
        items.put("type", "array");
        items.put("description", "Items listed in CORRECT order with sequential IDs (1=first, 2=second, 3=third, etc.). System shuffles for display.");
        items.put("minItems", 3);
        items.put("maxItems", 10);
        
        ObjectNode itemSchema = objectMapper.createObjectNode();
        itemSchema.put("type", "object");
        itemSchema.put("additionalProperties", false);
        
        ArrayNode itemRequired = objectMapper.createArrayNode();
        itemRequired.add("id");
        itemRequired.add("text");
        itemSchema.set("required", itemRequired);
        
        ObjectNode itemProps = objectMapper.createObjectNode();
        
        ObjectNode itemId = objectMapper.createObjectNode();
        itemId.put("type", "integer");
        itemId.put("description", "Sequential ID matching position in correct order (1=first, 2=second, 3=third, etc.)");
        itemId.put("minimum", 1);
        itemProps.set("id", itemId);
        
        ObjectNode itemText = objectMapper.createObjectNode();
        itemText.put("type", "string");
        itemText.put("description", "Clear, self-contained text describing this step/stage in the sequence");
        itemText.put("minLength", 5);
        itemText.put("maxLength", 200);
        itemProps.set("text", itemText);
        
        itemSchema.set("properties", itemProps);
        items.set("items", itemSchema);
        
        properties.set("items", items);
        content.set("properties", properties);
        return content;
    }
    
    /**
     * Schema for MATCHING content
     * Prompt template shows: left: [{id, text, matchId}], right: [{id, text}]
     * Parser: Generic (accepts any content structure)
     */
    private ObjectNode createMatchingContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("left");
        required.add("right");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        // Left items array (with matchId pointing to right item)
        ObjectNode left = objectMapper.createObjectNode();
        left.put("type", "array");
        left.put("description", "Left items to be matched (with matchId references)");
        left.put("minItems", 4);
        
        ObjectNode leftItem = objectMapper.createObjectNode();
        leftItem.put("type", "object");
        leftItem.put("additionalProperties", false);
        
        ArrayNode leftRequired = objectMapper.createArrayNode();
        leftRequired.add("id");
        leftRequired.add("text");
        leftRequired.add("matchId");
        leftItem.set("required", leftRequired);
        
        ObjectNode leftProps = objectMapper.createObjectNode();
        
        ObjectNode leftId = objectMapper.createObjectNode();
        leftId.put("type", "integer");
        leftId.put("description", "Unique ID for this left item");
        leftProps.set("id", leftId);
        
        ObjectNode leftText = objectMapper.createObjectNode();
        leftText.put("type", "string");
        leftText.put("description", "Text content of left item");
        leftProps.set("text", leftText);
        
        ObjectNode matchId = objectMapper.createObjectNode();
        matchId.put("type", "integer");
        matchId.put("description", "ID of the matching right item");
        leftProps.set("matchId", matchId);
        
        leftItem.set("properties", leftProps);
        left.set("items", leftItem);
        properties.set("left", left);
        
        // Right items array
        ObjectNode right = objectMapper.createObjectNode();
        right.put("type", "array");
        right.put("description", "Right items to be matched against");
        right.put("minItems", 4);
        
        ObjectNode rightItem = objectMapper.createObjectNode();
        rightItem.put("type", "object");
        rightItem.put("additionalProperties", false);
        
        ArrayNode rightRequired = objectMapper.createArrayNode();
        rightRequired.add("id");
        rightRequired.add("text");
        rightItem.set("required", rightRequired);
        
        ObjectNode rightProps = objectMapper.createObjectNode();
        
        ObjectNode rightId = objectMapper.createObjectNode();
        rightId.put("type", "integer");
        rightId.put("description", "Unique ID for this right item");
        rightProps.set("id", rightId);
        
        ObjectNode rightText = objectMapper.createObjectNode();
        rightText.put("type", "string");
        rightText.put("description", "Text content of right item");
        rightProps.set("text", rightText);
        
        rightItem.set("properties", rightProps);
        right.set("items", rightItem);
        properties.set("right", right);
        
        content.set("properties", properties);
        return content;
    }
    
    /**
     * Schema for HOTSPOT content
     * Parser expects: content.imageUrl and content.regions [{id: int, x: int, y: int, width: int, height: int, correct: boolean}]
     */
    private ObjectNode createHotspotContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("imageUrl");
        required.add("regions");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("type", "string");
        imageUrl.put("description", "URL or reference to the image");
        properties.set("imageUrl", imageUrl);
        
        ObjectNode regions = objectMapper.createObjectNode();
        regions.put("type", "array");
        regions.put("description", "Clickable hotspot regions with unique IDs");
        regions.put("minItems", 2);
        regions.put("maxItems", 6);
        
        ObjectNode regionItem = objectMapper.createObjectNode();
        regionItem.put("type", "object");
        regionItem.put("additionalProperties", false);
        
        ArrayNode regionRequired = objectMapper.createArrayNode();
        regionRequired.add("id");
        regionRequired.add("x");
        regionRequired.add("y");
        regionRequired.add("width");
        regionRequired.add("height");
        regionRequired.add("correct");
        regionItem.set("required", regionRequired);
        
        ObjectNode regionProps = objectMapper.createObjectNode();
        
        ObjectNode id = objectMapper.createObjectNode();
        id.put("type", "integer");
        id.put("description", "Unique ID for this region");
        regionProps.set("id", id);
        
        ObjectNode x = objectMapper.createObjectNode();
        x.put("type", "integer");
        x.put("description", "X coordinate (non-negative)");
        x.put("minimum", 0);
        regionProps.set("x", x);
        
        ObjectNode y = objectMapper.createObjectNode();
        y.put("type", "integer");
        y.put("description", "Y coordinate (non-negative)");
        y.put("minimum", 0);
        regionProps.set("y", y);
        
        ObjectNode width = objectMapper.createObjectNode();
        width.put("type", "integer");
        width.put("description", "Width of the region (non-negative)");
        width.put("minimum", 0);
        regionProps.set("width", width);
        
        ObjectNode height = objectMapper.createObjectNode();
        height.put("type", "integer");
        height.put("description", "Height of the region (non-negative)");
        height.put("minimum", 0);
        regionProps.set("height", height);
        
        ObjectNode correct = objectMapper.createObjectNode();
        correct.put("type", "boolean");
        correct.put("description", "Whether this region is a correct hotspot");
        regionProps.set("correct", correct);
        
        regionItem.set("properties", regionProps);
        regions.set("items", regionItem);
        
        properties.set("regions", regions);
        content.set("properties", properties);
        return content;
    }
    
    /**
     * Schema for COMPLIANCE content
     * Parser expects: content.statements [{id: int, text: string, compliant: boolean}]
     */
    private ObjectNode createComplianceContentSchema() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "object");
        content.put("additionalProperties", false);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("statements");
        content.set("required", required);
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode statements = objectMapper.createObjectNode();
        statements.put("type", "array");
        statements.put("description", "Compliance statements with unique IDs");
        statements.put("minItems", 2);
        statements.put("maxItems", 6);
        
        ObjectNode statementItem = objectMapper.createObjectNode();
        statementItem.put("type", "object");
        statementItem.put("additionalProperties", false);
        
        ArrayNode statementRequired = objectMapper.createArrayNode();
        statementRequired.add("id");
        statementRequired.add("text");
        statementRequired.add("compliant");
        statementItem.set("required", statementRequired);
        
        ObjectNode statementProps = objectMapper.createObjectNode();
        
        ObjectNode id = objectMapper.createObjectNode();
        id.put("type", "integer");
        id.put("description", "Unique ID for this statement");
        statementProps.set("id", id);
        
        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "string");
        text.put("description", "The compliance statement or action");
        statementProps.set("text", text);
        
        ObjectNode compliant = objectMapper.createObjectNode();
        compliant.put("type", "boolean");
        compliant.put("description", "Whether this statement/action is compliant");
        statementProps.set("compliant", compliant);
        
        statementItem.set("properties", statementProps);
        statements.set("items", statementItem);
        
        properties.set("statements", statements);
        content.set("properties", properties);
        return content;
    }
}
