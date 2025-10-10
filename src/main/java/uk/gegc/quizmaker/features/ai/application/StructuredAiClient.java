package uk.gegc.quizmaker.features.ai.application;

import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;

/**
 * Interface for structured AI question generation using schema-validated responses.
 * 
 * Phase 2 of structured output migration - abstracts LLM interaction behind
 * a clean interface that shields service layer from vendor specifics.
 * 
 * Design rationale:
 * - Isolates Spring AI specifics (ChatClient, JsonSchemaChatOptions) from domain logic
 * - Enables easy mocking in tests
 * - Allows future provider swaps (OpenAI → Claude → custom) without touching services
 * - Centralizes retry and error handling logic
 */
public interface StructuredAiClient {
    
    /**
     * Generate questions with structured output using JSON schema validation.
     * 
     * The LLM will be constrained to return responses matching the schema for
     * the requested question type. This eliminates manual parsing and provides
     * field-level validation.
     * 
     * @param request The generation request with all parameters
     * @return Response with validated questions and metadata
     * @throws uk.gegc.quizmaker.shared.exception.AiServiceException if generation fails
     */
    StructuredQuestionResponse generateQuestions(StructuredQuestionRequest request);
    
    /**
     * Regenerate questions for specific missing types.
     * Used in redistribution logic when initial generation didn't produce all requested types.
     * 
     * @param request The base generation request
     * @param missingTypes List of question types that need to be generated
     * @return Response with questions of the missing types
     * @throws uk.gegc.quizmaker.shared.exception.AiServiceException if generation fails
     */
    StructuredQuestionResponse regenerateMissingTypes(
            StructuredQuestionRequest request, 
            List<QuestionType> missingTypes
    );
    
    /**
     * Validate that the current model configuration supports structured output.
     * Should be called at startup to fail fast if misconfigured.
     * 
     * @return true if structured output is supported
     */
    boolean supportsStructuredOutput();
}

