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
     * Uses provider-enforced schema output when supported, otherwise complete
     * schema instructions in the prompt. Responses pass the same server-side
     * question validation regardless of delivery mode.
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
     * Check whether structured generation is available. Prompt-schema providers
     * can generate structured questions without provider-enforced JSON Schema.
     * This is not a remote model-capability probe.
     * 
     * @return true if structured output is supported
     */
    boolean supportsStructuredOutput();
}

