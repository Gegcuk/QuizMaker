package uk.gegc.quizmaker.features.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/**
 * Structured representation of a single question returned from the LLM.
 * Mirrors the platform's Question entity fields for seamless conversion.
 * 
 * Phase 1 of structured output migration - schema-validated question structure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredQuestion {
    
    /**
     * The question text/stem
     */
    @JsonProperty("questionText")
    private String questionText;
    
    /**
     * Question type (should match request)
     */
    @JsonProperty("type")
    private QuestionType type;
    
    /**
     * Difficulty level (should match request)
     */
    @JsonProperty("difficulty")
    private Difficulty difficulty;
    
    /**
     * Type-specific content as JSON string
     * Structure depends on question type (MCQ options, fill-gap blanks, etc.)
     */
    @JsonProperty("content")
    private String content;
    
    /**
     * Optional hint text
     */
    @JsonProperty("hint")
    private String hint;
    
    /**
     * Optional explanation text
     */
    @JsonProperty("explanation")
    private String explanation;
    
    /**
     * Confidence score from LLM (0.0-1.0), if supported
     */
    @JsonProperty("confidence")
    @Builder.Default
    private Double confidence = 1.0;
}

