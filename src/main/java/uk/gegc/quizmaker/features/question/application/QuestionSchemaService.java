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
                        // Return the full question (questionText, hint, explanation, content, etc.)
                        return firstQuestion;
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
     * Returns full question structure with questionText, hint, explanation, and content
     */
    private JsonNode createProgrammaticExample(QuestionType type) {
        // Create the content field specific to each type
        JsonNode content = switch (type) {
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
                    .put("text", "The capital of France is ___.")
                    .set("gaps", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", 1)
                                    .put("answer", "Paris")));
            
            case ORDERING -> objectMapper.createObjectNode()
                    .set("items", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", 1)
                                    .put("text", "First step"))
                            .add(objectMapper.createObjectNode()
                                    .put("id", 2)
                                    .put("text", "Second step"))
                            .add(objectMapper.createObjectNode()
                                    .put("id", 3)
                                    .put("text", "Third step")));
            
            case MATCHING -> {
                var matchContent = objectMapper.createObjectNode();
                matchContent.set("left", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("id", 1)
                                .put("text", "Term 1")
                                .put("matchId", 1))
                        .add(objectMapper.createObjectNode()
                                .put("id", 2)
                                .put("text", "Term 2")
                                .put("matchId", 2)));
                matchContent.set("right", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("id", 1)
                                .put("text", "Definition 1"))
                        .add(objectMapper.createObjectNode()
                                .put("id", 2)
                                .put("text", "Definition 2")));
                yield matchContent;
            }
            
            case HOTSPOT -> objectMapper.createObjectNode()
                    .put("imageUrl", "https://example.com/image.png")
                    .set("regions", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", 1)
                                    .put("x", 100)
                                    .put("y", 150)
                                    .put("width", 40)
                                    .put("height", 40)
                                    .put("correct", true))
                            .add(objectMapper.createObjectNode()
                                    .put("id", 2)
                                    .put("x", 200)
                                    .put("y", 200)
                                    .put("width", 40)
                                    .put("height", 40)
                                    .put("correct", false)));
            
            case COMPLIANCE -> objectMapper.createObjectNode()
                    .set("statements", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("id", 1)
                                    .put("text", "Practice complies with GDPR requirements")
                                    .put("compliant", true))
                            .add(objectMapper.createObjectNode()
                                    .put("id", 2)
                                    .put("text", "Practice violates user privacy rights")
                                    .put("compliant", false)));
        };
        
        // Wrap content in a full question structure
        var fullQuestion = objectMapper.createObjectNode();
        fullQuestion.put("questionText", getExampleQuestionText(type));
        fullQuestion.put("type", type.name());
        fullQuestion.put("difficulty", "MEDIUM");
        fullQuestion.set("content", content);
        fullQuestion.put("hint", getExampleHint(type));
        fullQuestion.put("explanation", getExampleExplanation(type));
        return fullQuestion;
    }
    
    private String getExampleQuestionText(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> "What is the primary function of mitochondria in cells?";
            case MCQ_MULTI -> "Which of the following are characteristics of mammals?";
            case TRUE_FALSE -> "Photosynthesis occurs only in plant cells.";
            case OPEN -> "Explain the process of cellular respiration and its importance.";
            case FILL_GAP -> "Complete the sentence about cellular respiration.";
            case ORDERING -> "Arrange these events in the history of SSL/TLS in chronological order.";
            case MATCHING -> "Match each cell organelle with its primary function.";
            case HOTSPOT -> "Click on the mitochondria in this cell diagram.";
            case COMPLIANCE -> "Which practices comply with GDPR data protection requirements?";
        };
    }
    
    private String getExampleHint(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> "Think about where cellular energy is produced.";
            case MCQ_MULTI -> "Consider all characteristics that define the mammal class.";
            case TRUE_FALSE -> "Consider where chloroplasts are found.";
            case OPEN -> "Consider the three main stages and ATP production.";
            case FILL_GAP -> "Think about where this process occurs and what it produces.";
            case ORDERING -> "SSL was developed before TLS.";
            case MATCHING -> "Each organelle has a specialized role in the cell.";
            case HOTSPOT -> "The mitochondria are bean-shaped structures.";
            case COMPLIANCE -> "GDPR requires explicit consent for data processing.";
        };
    }
    
    private String getExampleExplanation(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> "Mitochondria are the powerhouses of the cell, producing ATP through cellular respiration.";
            case MCQ_MULTI -> "Mammals are warm-blooded vertebrates with hair/fur that produce milk for their young.";
            case TRUE_FALSE -> "False. Photosynthesis occurs in organisms with chloroplasts, including some bacteria and protists.";
            case OPEN -> "Cellular respiration converts glucose to ATP through glycolysis, Krebs cycle, and electron transport chain.";
            case FILL_GAP -> "Cellular respiration occurs in mitochondria and produces ATP for cellular energy.";
            case ORDERING -> "SSL 2.0 (1995) → SSL 3.0 (1996) → TLS 1.0 (1999) → TLS 1.1 (2006) → TLS 1.2 (2008) → TLS 1.3 (2018).";
            case MATCHING -> "Each organelle has evolved to perform specific functions critical to cell survival and operation.";
            case HOTSPOT -> "Mitochondria are typically located throughout the cytoplasm and are responsible for ATP production.";
            case COMPLIANCE -> "GDPR requires explicit consent, purpose limitation, and data minimization for lawful processing.";
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

