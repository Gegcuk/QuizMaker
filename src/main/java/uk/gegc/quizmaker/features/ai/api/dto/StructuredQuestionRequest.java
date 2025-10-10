package uk.gegc.quizmaker.features.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for structured question generation.
 * Bundles all parameters needed by the LLM for generating questions
 * with schema-validated responses.
 * 
 * Phase 1 of structured output migration - centralizes generation parameters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredQuestionRequest {
    
    /**
     * Document ID this request relates to (optional for text-based generation)
     */
    private UUID documentId;
    
    /**
     * Index of the chunk being processed (for tracking and logging)
     */
    private Integer chunkIndex;
    
    /**
     * The actual text content to generate questions from
     */
    @NotBlank(message = "Chunk content cannot be blank")
    private String chunkContent;
    
    /**
     * Type of question to generate (MCQ_SINGLE, OPEN, etc.)
     */
    @NotNull(message = "Question type cannot be null")
    private QuestionType questionType;
    
    /**
     * Number of questions to generate
     */
    @Positive(message = "Question count must be positive")
    private int questionCount;
    
    /**
     * Difficulty level for questions
     */
    @NotNull(message = "Difficulty cannot be null")
    private Difficulty difficulty;
    
    /**
     * Target language for question generation (ISO 639-1 code)
     * Default: "en"
     */
    @Builder.Default
    private String language = "en";
    
    /**
     * Optional metadata for tracking and analytics
     */
    @Builder.Default
    private Map<String, String> metadata = Map.of();
}

