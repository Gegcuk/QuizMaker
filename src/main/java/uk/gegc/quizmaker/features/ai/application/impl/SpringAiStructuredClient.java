package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import uk.gegc.quizmaker.features.question.application.FillGapContentValidator;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

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
 * 
 * TODO: Timeout Configuration
 *   ChatClient calls use default timeout from Spring AI properties.
 *   For production, consider explicit timeout via ChatOptions to avoid indefinite waits.
 *   Example: chatClient.prompt(...).options(ChatOptions.builder().timeout(Duration.ofSeconds(30)).build())
 *   Recommended: Configure via application.properties:
 *     spring.ai.openai.chat.options.timeout=30s
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
    
    /**
     * Maximum completion tokens to prevent truncated JSON responses.
     * Default: 16000 tokens (sufficient for 10 complex questions)
     * Prevents hitting model's hard limit (32k for gpt-4.1-mini) which causes truncated JSON.
     */
    @Value("${spring.ai.openai.chat.options.max-tokens:16000}")
    private Integer maxCompletionTokens;
    
    @Override
    public StructuredQuestionResponse generateQuestions(StructuredQuestionRequest request) {
        validateRequest(request);
        
        int maxRetries = rateLimitConfig.getMaxRetries();
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            // Check for cancellation before each attempt (Phase 3 fix)
            if (request.getCancellationChecker() != null && request.getCancellationChecker().get()) {
                log.info("Generation cancelled before attempt {} for {} type {}",
                        retryCount + 1, request.getQuestionCount(), request.getQuestionType());
                return StructuredQuestionResponse.builder()
                        .questions(List.of())
                        .warnings(List.of("Generation cancelled by user"))
                        .tokensUsed(0L)
                        .build();
            }
            
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
        log.info("Supported models: OpenAI (gpt-4o*, gpt-4.1*, gpt-4o-mini), Anthropic (claude-3*)");
        
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
        
        // Get AI-safe JSON schema for this question type (media stripped)
        JsonNode schema = schemaRegistry.getSchemaForQuestionTypeAi(request.getQuestionType());
        
        // Build system message with structured output instructions (schema enforced server-side)
        String systemPrompt = promptTemplateService.buildSystemPrompt();

        if (log.isDebugEnabled()) {
            log.debug("Sending structured generation request for {} {} questions (schema enforced)",
                    request.getQuestionCount(), request.getQuestionType());
            log.debug("Schema snapshot for {}: {}", request.getQuestionType(),
                    schema.toString().length() > 500
                            ? schema.toString().substring(0, 500) + "..."
                            : schema.toString());
        }

        OpenAiChatOptions chatOptions = buildChatOptions(request.getQuestionType(), schema);

        Prompt prompt = chatOptions != null
                ? new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)
                ), chatOptions)
                : new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)
                ));

        if (log.isDebugEnabled() && chatOptions != null && chatOptions.getResponseFormat() != null) {
            log.debug("Using response format: {} (schema name: {})",
                    chatOptions.getResponseFormat().getType(),
                    chatOptions.getResponseFormat().getJsonSchema() != null
                            ? chatOptions.getResponseFormat().getJsonSchema().getName()
                            : "n/a");
        }

        ChatResponse response = chatClient.prompt(prompt)
                .call()
                .chatResponse();
        
        if (response == null || response.getResult() == null) {
            throw new AiServiceException("No response received from AI service");
        }
        
        String rawResponse = response.getResult().getOutput().getText();

        if (log.isDebugEnabled() && response.getMetadata() != null) {
            log.debug("Structured response metadata: model={}, usage={}",
                    response.getMetadata().getModel(),
                    response.getMetadata().getUsage());
        }

        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            throw new AiServiceException("Empty response received from AI service");
        }

        if (log.isDebugEnabled()) {
            String preview = rawResponse.length() > 1000 ? rawResponse.substring(0, 1000) + "..." : rawResponse;
            log.debug("Structured raw response preview: {}", preview);
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

    private OpenAiChatOptions buildChatOptions(QuestionType questionType, JsonNode schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            ResponseFormat.JsonSchema jsonSchema = ResponseFormat.JsonSchema.builder()
                    .name(questionType.name().toLowerCase() + "_schema")
                    .schema(schemaJson)
                    .strict(true)
                    .build();

            ResponseFormat responseFormat = ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(jsonSchema)
                    .build();

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .responseFormat(responseFormat)
                    .maxTokens(maxCompletionTokens)
                    .build();

            if (log.isDebugEnabled()) {
                log.debug("Configured structured response format for {} with schema name '{}', maxTokens={}",
                        questionType, jsonSchema.getName(), maxCompletionTokens);
            }

            return options;

        } catch (Exception e) {
            log.error("Failed to build JSON schema response format for {}", questionType, e);
            return null;
        }
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
            
            // Check if this is a truncation error (EOF while parsing)
            if (e.getMessage() != null && 
                (e.getMessage().contains("end-of-input") || 
                 e.getMessage().contains("Unexpected end") ||
                 e.getMessage().contains("EOF"))) {
                log.error("JSON response appears to be truncated. This may indicate:");
                log.error("  1. max-tokens ({}) is too high and hit model's hard limit", maxCompletionTokens);
                log.error("  2. Question count ({}) is too large for the configured token limit", expectedType);
                log.error("  3. Content complexity requires fewer questions or higher token limit");
                throw new AIResponseParseException(
                    "JSON response truncated due to token limit. " +
                    "Current max-tokens: " + maxCompletionTokens + ". " +
                    "Try reducing question count or increasing max-tokens in configuration.", e);
            }
            
            throw new AIResponseParseException("Invalid JSON in structured response: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parse a single question from JSON node
     */
    private StructuredQuestion parseQuestion(JsonNode questionNode) {
        StructuredQuestion.StructuredQuestionBuilder builder = StructuredQuestion.builder();
        
        // Required fields (strict mode requires all fields to be present and non-null)
        if (!questionNode.has("questionText") || questionNode.get("questionText").isNull()
                || !questionNode.has("type") || questionNode.get("type").isNull()
                || !questionNode.has("difficulty") || questionNode.get("difficulty").isNull()
                || !questionNode.has("content") || questionNode.get("content").isNull()
                || !questionNode.has("hint") || questionNode.get("hint").isNull()
                || !questionNode.has("explanation") || questionNode.get("explanation").isNull()
                || !questionNode.has("confidence") || questionNode.get("confidence").isNull()) {
            throw new AIResponseParseException("Question missing required fields or has null values");
        }
        
        builder.questionText(questionNode.get("questionText").asText());
        QuestionType type = QuestionType.valueOf(questionNode.get("type").asText());
        builder.type(type);
        builder.difficulty(Difficulty.valueOf(
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
        
        // Now required fields (strict mode)
        builder.hint(questionNode.get("hint").asText());
        builder.explanation(questionNode.get("explanation").asText());
        builder.confidence(questionNode.get("confidence").asDouble());
        
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
                validateFillGapContent(contentNode);
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
    
    private void validateFillGapContent(JsonNode contentNode) {
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(contentNode);
        if (!result.valid()) {
            throw new AIResponseParseException(result.errorMessage());
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
