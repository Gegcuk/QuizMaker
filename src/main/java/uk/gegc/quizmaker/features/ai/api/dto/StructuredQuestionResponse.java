package uk.gegc.quizmaker.features.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for structured question generation.
 * Contains the generated questions plus metadata about the generation process.
 * 
 * Phase 1 of structured output migration - wraps LLM response with diagnostics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredQuestionResponse {
    
    /**
     * List of successfully generated questions
     */
    @Builder.Default
    private List<StructuredQuestion> questions = new ArrayList<>();
    
    /**
     * Warning messages (e.g., partial failures, field omissions)
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    
    /**
     * Total tokens used in this generation (if available from LLM metadata)
     */
    @Builder.Default
    private Long tokensUsed = 0L;
    
    /**
     * Whether the schema validation passed completely
     */
    @Builder.Default
    private boolean schemaValid = true;
    
    /**
     * Schema validation error details, if any
     */
    private String schemaValidationError;
}

