package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI implementation of StructuredAiClient.
 * Wraps ChatClient with JSON schema validation for structured question generation.
 * 
 * Phase 2 of structured output migration - infrastructure layer implementation.
 * 
 * Design notes:
 * - Uses Spring AI's ChatClient for LLM communication
 * - Applies JSON schema from QuestionSchemaRegistry to constrain responses
 * - Captures raw response + validation errors for observability
 * - Implements retry logic with exponential backoff for rate limits
 * - Falls back to legacy parsing if structured output fails (future enhancement)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpringAiStructuredClient implements StructuredAiClient {
    
    private final ChatClient chatClient;
    private final QuestionSchemaRegistry schemaRegistry;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final AiRateLimitConfig rateLimitConfig;
    
    @Override
    public StructuredQuestionResponse generateQuestions(StructuredQuestionRequest request) {
        validateRequest(request);
        
        int maxRetries = rateLimitConfig.getMaxRetries();
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                return attemptGeneration(request);
            } catch (Exception e) {
                if (isRateLimitError(e) && retryCount < maxRetries - 1) {
                    long delayMs = calculateBackoffDelay(retryCount);
                    log.warn("Rate limit hit for structured generation (attempt {}). Waiting {} ms",
                            retryCount + 1, delayMs);
                    sleepForRateLimit(delayMs);
                    retryCount++;
                } else if (retryCount < maxRetries - 1) {
                    log.warn("Structured generation attempt {} failed: {}", retryCount + 1, e.getMessage());
                    retryCount++;
                } else {
                    log.error("Structured generation failed after {} attempts", maxRetries, e);
                    throw new AiServiceException(
                            "Failed to generate structured questions after " + maxRetries + " attempts: " 
                            + e.getMessage(), e);
                }
            }
        }
        
        throw new AiServiceException("Failed to generate structured questions after " + maxRetries + " attempts");
    }
    
    @Override
    public StructuredQuestionResponse regenerateMissingTypes(
            StructuredQuestionRequest request,
            List<QuestionType> missingTypes) {
        
        log.info("Regenerating missing question types: {}", missingTypes);
        
        // For now, regenerate each type independently and merge results
        // Future enhancement: use composite schema with oneOf for batch generation
        List<StructuredQuestion> allQuestions = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();
        long totalTokens = 0L;
        
        for (QuestionType missingType : missingTypes) {
            StructuredQuestionRequest typeRequest = StructuredQuestionRequest.builder()
                    .documentId(request.getDocumentId())
                    .chunkIndex(request.getChunkIndex())
                    .chunkContent(request.getChunkContent())
                    .questionType(missingType)
                    .questionCount(request.getQuestionCount())
                    .difficulty(request.getDifficulty())
                    .language(request.getLanguage())
                    .metadata(request.getMetadata())
                    .build();
            
            try {
                StructuredQuestionResponse response = generateQuestions(typeRequest);
                allQuestions.addAll(response.getQuestions());
                allWarnings.addAll(response.getWarnings());
                totalTokens += response.getTokensUsed();
            } catch (Exception e) {
                log.warn("Failed to regenerate type {}: {}", missingType, e.getMessage());
                allWarnings.add("Failed to regenerate " + missingType + ": " + e.getMessage());
            }
        }
        
        return StructuredQuestionResponse.builder()
                .questions(allQuestions)
                .warnings(allWarnings)
                .tokensUsed(totalTokens)
                .schemaValid(true)
                .build();
    }
    
    @Override
    public boolean supportsStructuredOutput() {
        // Check if Spring AI and ChatClient are available (always true if we're here)
        if (chatClient == null) {
            log.warn("ChatClient not available - structured output not supported");
            return false;
        }
        
        // Models known to support JSON mode / structured output
        // OpenAI: gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-4.1-mini, gpt-3.5-turbo-1106+
        // Anthropic: claude-3-5-sonnet, claude-3-opus, claude-3-sonnet
        // Note: This is a best-effort check. In Phase 3, read from configuration.
        log.info("Structured output support check - Spring AI 1.0.0-M6+ with ChatClient available");
        log.info("Supported models: OpenAI (gpt-4o*, gpt-4-turbo, gpt-4.1*), Anthropic (claude-3*)");
        
        // For now, return true if ChatClient exists
        // Phase 3 TODO: Read spring.ai.openai.chat.options.model from config and validate
        return true;
    }
    
    /**
     * Attempt to generate questions with structured output
     */
    private StructuredQuestionResponse attemptGeneration(StructuredQuestionRequest request) {
        // Build prompt using existing template service
        String userPrompt = promptTemplateService.buildPromptForChunk(
                request.getChunkContent(),
                request.getQuestionType(),
                request.getQuestionCount(),
                request.getDifficulty(),
                request.getLanguage()
        );
        
        // Get JSON schema for this question type
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(request.getQuestionType());
        
        // Build system message that includes schema instruction
        String systemPrompt = buildSystemPromptWithSchema(schema);
        
        // Call LLM with structured output request
        log.debug("Sending structured generation request for {} {} questions",
                request.getQuestionCount(), request.getQuestionType());
        
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
        
        ChatResponse response = chatClient.prompt(prompt)
                .call()
                .chatResponse();
        
        if (response == null || response.getResult() == null) {
            throw new AiServiceException("No response received from AI service");
        }
        
        String rawResponse = response.getResult().getOutput().getText();
        
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            throw new AiServiceException("Empty response received from AI service");
        }
        
        // Parse and validate response
        StructuredQuestionResponse structuredResponse = parseStructuredResponse(
                rawResponse, 
                request.getQuestionType(),
                schema
        );
        
        // Add token usage metadata if available
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Long totalTokens = Long.valueOf(response.getMetadata().getUsage().getTotalTokens());
            structuredResponse.setTokensUsed(totalTokens);
        }
        
        log.info("Successfully generated {} structured questions of type {}",
                structuredResponse.getQuestions().size(), request.getQuestionType());
        
        return structuredResponse;
    }
    
    /**
     * Build system prompt that instructs the model to follow JSON schema
     */
    private String buildSystemPromptWithSchema(JsonNode schema) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert question generator for educational content.\n\n");
        prompt.append("CRITICAL: You must respond with valid JSON that exactly matches this schema:\n\n");
        
        try {
            prompt.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize schema", e);
            prompt.append(schema.toString());
        }
        
        prompt.append("\n\nIMPORTANT RULES:\n");
        prompt.append("1. Return ONLY valid JSON - no markdown, no explanation, no preamble\n");
        prompt.append("2. Follow the schema exactly - all required fields must be present\n");
        prompt.append("3. Ensure the 'content' field is a valid JSON string (properly escaped)\n");
        prompt.append("4. Respect all type constraints and validation rules\n");
        prompt.append("5. Generate high-quality, educational questions based on the provided content\n");
        
        return prompt.toString();
    }
    
    /**
     * Parse and validate the structured response from LLM
     */
    private StructuredQuestionResponse parseStructuredResponse(
            String rawResponse, 
            QuestionType expectedType,
            JsonNode schema) {
        
        List<String> warnings = new ArrayList<>();
        
        try {
            // Clean response (remove markdown code blocks if present)
            String cleanedResponse = cleanJsonResponse(rawResponse);
            
            // Parse as JSON
            JsonNode responseNode = objectMapper.readTree(cleanedResponse);
            
            // Validate against schema (basic validation)
            if (!responseNode.has("questions")) {
                throw new AIResponseParseException("Response missing 'questions' field");
            }
            
            JsonNode questionsNode = responseNode.get("questions");
            if (!questionsNode.isArray()) {
                throw new AIResponseParseException("'questions' field must be an array");
            }
            
            // Parse questions
            List<StructuredQuestion> questions = new ArrayList<>();
            for (JsonNode questionNode : questionsNode) {
                try {
                    StructuredQuestion question = parseQuestion(questionNode);
                    
                    // Validate question type matches request
                    if (question.getType() != expectedType) {
                        warnings.add("Question type mismatch: expected " + expectedType 
                                + " but got " + question.getType());
                    }
                    
                    questions.add(question);
                } catch (Exception e) {
                    warnings.add("Failed to parse question: " + e.getMessage());
                    log.warn("Failed to parse individual question", e);
                }
            }
            
            if (questions.isEmpty()) {
                throw new AIResponseParseException("No valid questions parsed from response");
            }
            
            return StructuredQuestionResponse.builder()
                    .questions(questions)
                    .warnings(warnings)
                    .schemaValid(true)
                    .build();
            
        } catch (JsonProcessingException e) {
            log.error("Failed to parse structured response as JSON", e);
            throw new AIResponseParseException("Invalid JSON in structured response: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parse a single question from JSON node
     */
    private StructuredQuestion parseQuestion(JsonNode questionNode) {
        StructuredQuestion.StructuredQuestionBuilder builder = StructuredQuestion.builder();
        
        // Required fields
        if (!questionNode.has("questionText") || !questionNode.has("type") 
                || !questionNode.has("difficulty") || !questionNode.has("content")) {
            throw new AIResponseParseException("Question missing required fields");
        }
        
        builder.questionText(questionNode.get("questionText").asText());
        QuestionType type = QuestionType.valueOf(questionNode.get("type").asText());
        builder.type(type);
        builder.difficulty(uk.gegc.quizmaker.features.question.domain.model.Difficulty.valueOf(
                questionNode.get("difficulty").asText()));
        
        // Content must be serialized as JSON string for storage
        JsonNode contentNode = questionNode.get("content");
        
        // Validate content structure per question type
        validateContentStructure(contentNode, type);
        
        try {
            String contentJson = objectMapper.writeValueAsString(contentNode);
            builder.content(contentJson);
        } catch (JsonProcessingException e) {
            throw new AIResponseParseException("Failed to serialize content: " + e.getMessage(), e);
        }
        
        // Optional fields
        if (questionNode.has("hint") && !questionNode.get("hint").isNull()) {
            builder.hint(questionNode.get("hint").asText());
        }
        
        if (questionNode.has("explanation") && !questionNode.get("explanation").isNull()) {
            builder.explanation(questionNode.get("explanation").asText());
        }
        
        if (questionNode.has("confidence") && !questionNode.get("confidence").isNull()) {
            builder.confidence(questionNode.get("confidence").asDouble());
        }
        
        return builder.build();
    }
    
    /**
     * Validate content structure matches question type requirements.
     * Catches schema drift early before Phase 3 integration.
     */
    private void validateContentStructure(JsonNode contentNode, QuestionType type) {
        switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> {
                if (!contentNode.has("options") || !contentNode.get("options").isArray()) {
                    throw new AIResponseParseException("MCQ question must have 'options' array in content");
                }
            }
            case TRUE_FALSE -> {
                if (!contentNode.has("answer") || !contentNode.get("answer").isBoolean()) {
                    throw new AIResponseParseException("TRUE_FALSE question must have boolean 'answer' in content");
                }
            }
            case OPEN -> {
                if (!contentNode.has("answer") || contentNode.get("answer").asText().trim().isEmpty()) {
                    throw new AIResponseParseException("OPEN question must have non-empty 'answer' in content");
                }
            }
            case FILL_GAP -> {
                if (!contentNode.has("text") || !contentNode.has("gaps")) {
                    throw new AIResponseParseException("FILL_GAP question must have 'text' and 'gaps' in content");
                }
                if (!contentNode.get("gaps").isArray()) {
                    throw new AIResponseParseException("FILL_GAP 'gaps' must be an array");
                }
            }
            case ORDERING -> {
                if (!contentNode.has("items") || !contentNode.get("items").isArray()) {
                    throw new AIResponseParseException("ORDERING question must have 'items' array in content");
                }
            }
            case MATCHING -> {
                if (!contentNode.has("left") || !contentNode.get("left").isArray() 
                        || !contentNode.has("right") || !contentNode.get("right").isArray()) {
                    throw new AIResponseParseException("MATCHING question must have 'left' and 'right' arrays in content");
                }
            }
            case HOTSPOT -> {
                if (!contentNode.has("imageUrl") || !contentNode.has("regions")) {
                    throw new AIResponseParseException("HOTSPOT question must have 'imageUrl' and 'regions' in content");
                }
                if (!contentNode.get("regions").isArray()) {
                    throw new AIResponseParseException("HOTSPOT 'regions' must be an array");
                }
            }
            case COMPLIANCE -> {
                if (!contentNode.has("statements") || !contentNode.get("statements").isArray()) {
                    throw new AIResponseParseException("COMPLIANCE question must have 'statements' array in content");
                }
            }
        }
    }
    
    /**
     * Clean JSON response by removing markdown code blocks
     */
    private String cleanJsonResponse(String response) {
        String cleaned = response.trim();
        
        // Remove markdown code blocks (```json ... ``` or ``` ... ```)
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        return cleaned.trim();
    }
    
    /**
     * Validate request before processing
     */
    private void validateRequest(StructuredQuestionRequest request) {
        if (request.getChunkContent() == null || request.getChunkContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Chunk content cannot be empty");
        }
        
        if (request.getQuestionType() == null) {
            throw new IllegalArgumentException("Question type cannot be null");
        }
        
        if (request.getQuestionCount() <= 0) {
            throw new IllegalArgumentException("Question count must be positive");
        }
        
        if (request.getDifficulty() == null) {
            throw new IllegalArgumentException("Difficulty cannot be null");
        }
    }
    
    /**
     * Check if exception is a rate limit error
     */
    private boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        return message.contains("429") || 
               message.contains("rate limit") || 
               message.contains("rate_limit_exceeded") ||
               message.contains("Too Many Requests") ||
               message.contains("TPM") ||
               message.contains("RPM");
    }
    
    /**
     * Calculate exponential backoff delay with jitter
     */
    private long calculateBackoffDelay(int retryCount) {
        long exponentialDelay = rateLimitConfig.getBaseDelayMs() * (long) Math.pow(2, retryCount);
        
        double jitterRange = rateLimitConfig.getJitterFactor();
        double jitter = (1.0 - jitterRange) + (Math.random() * 2 * jitterRange);
        
        long delayWithJitter = (long) (exponentialDelay * jitter);
        
        return Math.min(delayWithJitter, rateLimitConfig.getMaxDelayMs());
    }
    
    /**
     * Sleep for rate limit delay
     */
    private void sleepForRateLimit(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting for rate limit", ie);
        }
    }
}

