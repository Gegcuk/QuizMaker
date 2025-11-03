package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.api.dto.QuestionSchemaResponse;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Service for retrieving question type schemas and examples.
 * Exposes QuestionSchemaRegistry data via API endpoints.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionSchemaService {

    private final QuestionSchemaRegistry questionSchemaRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Get schemas and examples for all question types
     */
    public Map<QuestionType, QuestionSchemaResponse> getAllQuestionSchemas() {
        Map<QuestionType, QuestionSchemaResponse> schemas = new EnumMap<>(QuestionType.class);
        
        for (QuestionType type : QuestionType.values()) {
            schemas.put(type, getQuestionSchema(type));
        }
        
        return schemas;
    }

    /**
     * Get schema and example for a specific question type
     */
    public QuestionSchemaResponse getQuestionSchema(QuestionType type) {
        JsonNode schema = questionSchemaRegistry.getSchemaForQuestionType(type);
        JsonNode example = loadExampleForType(type);
        String description = getDescriptionForType(type);
        
        return new QuestionSchemaResponse(schema, example, description);
    }

    /**
     * Load example JSON from resources/prompts/examples/
     * Falls back to programmatic example if file doesn't exist
     */
    private JsonNode loadExampleForType(QuestionType type) {
        String filename = getExampleFilename(type);
        
        try {
            ClassPathResource resource = new ClassPathResource("prompts/examples/" + filename);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    JsonNode fullExample = objectMapper.readTree(is);
                    // Extract first question from the "questions" array
                    if (fullExample.has("questions") && fullExample.get("questions").isArray() 
                            && fullExample.get("questions").size() > 0) {
                        JsonNode firstQuestion = fullExample.get("questions").get(0);
                        // Return just the content field as the example
                        if (firstQuestion.has("content")) {
                            return firstQuestion.get("content");
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not load example file for {}: {}", type, e.getMessage());
        }
        
        // Fallback to programmatic example
        return createProgrammaticExample(type);
    }

    /**
     * Get example filename for a question type
     */
    private String getExampleFilename(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> "mcq-single-example.json";
            case MCQ_MULTI -> "mcq-multi-example.json";
            case TRUE_FALSE -> "true-false-example.json";
            case OPEN -> "open-question-example.json";
            case FILL_GAP -> "fill-gap-example.json";
            case ORDERING -> "ordering-example.json";
            case MATCHING -> "matching-example.json";
            case HOTSPOT -> "hotspot-example.json";
            case COMPLIANCE -> "compliance-example.json";
        };
    }

    /**
     * Create programmatic example if file doesn't exist
     */
    private JsonNode createProgrammaticExample(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> objectMapper.createObjectNode()
                    .set("options", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", "a")
                                    .put("text", "Option A")
                                    .put("correct", false))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "b")
                                    .put("text", "Option B")
                                    .put("correct", true))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "c")
                                    .put("text", "Option C")
                                    .put("correct", false))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "d")
                                    .put("text", "Option D")
                                    .put("correct", false)));
            
            case MCQ_MULTI -> objectMapper.createObjectNode()
                    .set("options", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", "a")
                                    .put("text", "Option A")
                                    .put("correct", true))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "b")
                                    .put("text", "Option B")
                                    .put("correct", false))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "c")
                                    .put("text", "Option C")
                                    .put("correct", true))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "d")
                                    .put("text", "Option D")
                                    .put("correct", false)));
            
            case TRUE_FALSE -> objectMapper.createObjectNode()
                    .put("answer", true);
            
            case OPEN -> objectMapper.createObjectNode()
                    .put("answer", "Sample answer text here");
            
            case FILL_GAP -> objectMapper.createObjectNode()
                    .put("template", "The capital of France is ___.")
                    .set("blanks", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("position", 0)
                                    .put("answer", "Paris")));
            
            case ORDERING -> objectMapper.createObjectNode()
                    .set("items", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", "1")
                                    .put("text", "First step")
                                    .put("correctOrder", 1))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "2")
                                    .put("text", "Second step")
                                    .put("correctOrder", 2))
                            .add(objectMapper.createObjectNode()
                                    .put("id", "3")
                                    .put("text", "Third step")
                                    .put("correctOrder", 3)));
            
            case MATCHING -> objectMapper.createObjectNode()
                    .set("pairs", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("left", "Term 1")
                                    .put("right", "Definition 1"))
                            .add(objectMapper.createObjectNode()
                                    .put("left", "Term 2")
                                    .put("right", "Definition 2")));
            
            case HOTSPOT -> objectMapper.createObjectNode()
                    .put("imageUrl", "https://example.com/image.png")
                    .set("hotspots", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("x", 100)
                                    .put("y", 150)
                                    .put("radius", 20)
                                    .put("correct", true)));
            
            case COMPLIANCE -> objectMapper.createObjectNode()
                    .put("statement", "This practice complies with GDPR")
                    .put("compliant", true);
        };
    }

    /**
     * Get human-readable description for each question type
     */
    private String getDescriptionForType(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> "Multiple choice question with exactly 4 options and 1 correct answer";
            case MCQ_MULTI -> "Multiple choice question with 4-6 options and multiple correct answers";
            case TRUE_FALSE -> "True or False question with boolean answer";
            case OPEN -> "Open-ended question with text answer";
            case FILL_GAP -> "Fill in the blank question with one or more gaps in a template";
            case ORDERING -> "Ordering question where items must be arranged in correct sequence";
            case MATCHING -> "Matching question with pairs of related items";
            case HOTSPOT -> "Image-based question where user clicks correct area(s)";
            case COMPLIANCE -> "Compliance question evaluating adherence to policy/regulation";
        };
    }
}

