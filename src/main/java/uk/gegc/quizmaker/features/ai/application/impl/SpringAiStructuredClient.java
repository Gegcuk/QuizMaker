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
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.AiProviderHttpException;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudget;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudgetExhaustedException;
import uk.gegc.quizmaker.features.ai.application.ProviderUsageObservation;
import uk.gegc.quizmaker.features.ai.application.ProviderUsagePersistenceException;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.application.FillGapContentValidator;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Spring AI implementation of StructuredAiClient.
 * Wraps ChatClient with JSON schema validation for structured question generation.
 * 
 * Phase 2 of structured output migration - infrastructure layer implementation.
 * 
 * Design notes:
 * - Uses Spring AI's ChatClient for LLM communication
 * - Applies JSON schema from QuestionSchemaRegistry to constrain responses
 * - Records bounded metadata and stable failure categories without logging provider content
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

    private static final long CANCELLATION_CHECK_INTERVAL_MS = 1_000L;
    
    private final ChatClient chatClient;
    private final QuestionSchemaRegistry schemaRegistry;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final AiRateLimitConfig rateLimitConfig;
    
    /**
     * Maximum completion tokens to prevent truncated JSON responses.
     * Default: 16000 tokens (sufficient for 10 complex questions)
     * Uses OpenAI's modern max_completion_tokens field, which is required by GPT-5 models.
     */
    @Value("${spring.ai.openai.chat.options.max-completion-tokens:16000}")
    private Integer maxCompletionTokens;
    
    @Override
    public StructuredQuestionResponse generateQuestions(StructuredQuestionRequest request) {
        validateRequest(request);
        
        int maxRetries = rateLimitConfig.getMaxRetries();
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            // Positive retry waits poll this same cooperative cancellation signal.
            if (isCancellationRequested(request.getCancellationChecker())) {
                log.info("Generation cancelled before attempt {} for {} type {}",
                        retryCount + 1, request.getQuestionCount(), request.getQuestionType());
                return cancelledResponse();
            }

            if (request.getProviderAttemptBudget() != null
                    && request.getProviderAttemptBudget().isExhausted()) {
                throw new ProviderAttemptBudgetExhaustedException();
            }
            
            try {
                return attemptGeneration(request);
            } catch (ProviderUsagePersistenceException exception) {
                throw exception;
            } catch (ProviderAttemptBudgetExhaustedException exception) {
                log.warn("Structured generation stopped with category {}",
                        GenerationFailureCategory.ATTEMPT_BUDGET_EXHAUSTED);
                throw exception;
            } catch (PromptConstructionException exception) {
                log.error("Structured generation stopped with category {}",
                        GenerationFailureCategory.PROMPT_CONSTRUCTION);
                throw new AiServiceException(
                        "Failed to generate structured questions: "
                                + GenerationFailureCategory.PROMPT_CONSTRUCTION);
            } catch (Exception e) {
                RetryDecision retryDecision = determineRetry(e, retryCount);
                if (retryDecision.retry() && retryCount < maxRetries - 1) {
                    if (request.getProviderAttemptBudget() != null
                            && request.getProviderAttemptBudget().isExhausted()) {
                        throw new ProviderAttemptBudgetExhaustedException();
                    }
                    log.warn("Structured generation attempt {} failed with category {}",
                            retryCount + 1, retryDecision.failureCategory());
                    if (retryDecision.delayMs() > 0) {
                        log.warn("Waiting {} ms before the next structured generation attempt",
                                retryDecision.delayMs());
                        if (!waitForRetry(
                                retryDecision.delayMs(),
                                request.getCancellationChecker())) {
                            log.info("Generation cancelled during retry wait after attempt {} for {} type {}",
                                    retryCount + 1,
                                    request.getQuestionCount(),
                                    request.getQuestionType());
                            return cancelledResponse();
                        }
                    }
                    retryCount++;
                } else {
                    int attemptsMade = retryCount + 1;
                    log.error("Structured generation failed on attempt {} with category {}",
                            attemptsMade, retryDecision.failureCategory());
                    throw new AiServiceException(
                            "Failed to generate structured questions after " + attemptsMade
                                    + (attemptsMade == 1 ? " attempt: " : " attempts: ")
                                    + safeFailureSummary(e, retryDecision.failureCategory()));
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
                    .cancellationChecker(request.getCancellationChecker())
                    .providerAttemptBudget(request.getProviderAttemptBudget())
                    .providerUsageObserver(request.getProviderUsageObserver())
                    .build();
            
            try {
                StructuredQuestionResponse response = generateQuestions(typeRequest);
                allQuestions.addAll(response.getQuestions());
                allWarnings.addAll(response.getWarnings());
                totalTokens += response.getTokensUsed();
            } catch (ProviderUsagePersistenceException exception) {
                throw exception;
            } catch (ProviderAttemptBudgetExhaustedException exception) {
                allWarnings.add("Provider attempt budget exhausted while regenerating missing types");
                break;
            } catch (Exception e) {
                GenerationFailureCategory failureCategory = classifyFailure(e);
                log.warn("Failed to regenerate type {} with category {}", missingType, failureCategory);
                allWarnings.add("Failed to regenerate " + missingType + " (" + failureCategory + ")");
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
        // OpenAI: GPT-4 and GPT-5 model families that expose structured output
        // Anthropic: claude-3-5-sonnet, claude-3-opus, claude-3-sonnet
        // Note: This is a best-effort check. In Phase 3, read from configuration.
        log.info("Structured output support check - Spring AI 1.0.0-M6+ with ChatClient available");
        log.info("Supported models: OpenAI (GPT-4/GPT-5 structured-output families), Anthropic (claude-3*)");
        
        // For now, return true if ChatClient exists
        // Phase 3 TODO: Read spring.ai.openai.chat.options.model from config and validate
        return true;
    }
    
    /**
     * Attempt to generate questions with structured output
     */
    private StructuredQuestionResponse attemptGeneration(StructuredQuestionRequest request) {
        String userPrompt;
        String systemPrompt;
        try {
            userPrompt = promptTemplateService.buildPromptForChunk(
                    request.getChunkContent(),
                    request.getQuestionType(),
                    request.getQuestionCount(),
                    request.getDifficulty(),
                    request.getLanguage()
            );
            systemPrompt = promptTemplateService.buildSystemPrompt();
        } catch (RuntimeException exception) {
            throw new PromptConstructionException();
        }
        
        // Get AI-safe JSON schema for this question type (media stripped)
        JsonNode schema = schemaRegistry.getSchemaForQuestionTypeAi(
                request.getQuestionType(),
                request.getDifficulty());

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

        ProviderAttemptBudget providerAttemptBudget = request.getProviderAttemptBudget();
        if (providerAttemptBudget != null && !providerAttemptBudget.tryAcquire()) {
            throw new ProviderAttemptBudgetExhaustedException();
        }
        UUID providerAttemptId = UUID.randomUUID();
        observeProviderUsage(request, ProviderUsageObservation.started(providerAttemptId));

        ChatResponse response;
        try {
            response = chatClient.prompt(prompt)
                    .call()
                    .chatResponse();
        } catch (RuntimeException exception) {
            observeProviderUsage(request, ProviderUsageObservation.failed(providerAttemptId));
            throw exception;
        }

        if (response == null) {
            observeProviderUsage(request, ProviderUsageObservation.failed(providerAttemptId));
            throw new AiServiceException("No response received from AI service");
        }
        observeProviderResponse(request, providerAttemptId, response);

        if (response.getResult() == null) {
            throw new AiServiceException("No response received from AI service");
        }
        
        String rawResponse = response.getResult().getOutput().getText();

        if (log.isDebugEnabled() && response.getMetadata() != null) {
            var usage = response.getMetadata().getUsage();
            Long totalTokens = usage != null && !(usage instanceof EmptyUsage)
                    ? Long.valueOf(usage.getTotalTokens())
                    : null;
            log.debug("Structured response metadata: model={}, totalTokens={}",
                    response.getMetadata().getModel(),
                    totalTokens);
        }

        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            throw new AiServiceException("Empty response received from AI service");
        }

        // Parse and validate response
        StructuredQuestionResponse structuredResponse = parseStructuredResponse(
                rawResponse, 
                request.getQuestionType(),
                schema
        );
        retainRequestedDifficulty(structuredResponse, request.getDifficulty());
        
        // Add token usage metadata if available
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Long totalTokens = Long.valueOf(response.getMetadata().getUsage().getTotalTokens());
            structuredResponse.setTokensUsed(totalTokens);
        }
        
        log.info("Successfully generated {} structured questions of type {}",
                structuredResponse.getQuestions().size(), request.getQuestionType());
        
        return structuredResponse;
    }

    private void observeProviderResponse(
            StructuredQuestionRequest request,
            UUID providerAttemptId,
            ChatResponse response
    ) {
        if (request.getProviderUsageObserver() == null) {
            return;
        }
        var usage = response != null && response.getMetadata() != null
                ? response.getMetadata().getUsage()
                : null;
        Long providerLlmTokens = usage != null && !(usage instanceof EmptyUsage)
                ? Long.valueOf(usage.getTotalTokens())
                : null;
        observeProviderUsage(
                request,
                providerLlmTokens == null
                        ? ProviderUsageObservation.missing(providerAttemptId)
                        : ProviderUsageObservation.reported(providerAttemptId, providerLlmTokens)
        );
    }

    private void observeProviderUsage(
            StructuredQuestionRequest request,
            ProviderUsageObservation observation
    ) {
        if (request.getProviderUsageObserver() != null) {
            request.getProviderUsageObserver().accept(observation);
        }
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
                    .maxCompletionTokens(maxCompletionTokens)
                    .build();

            if (log.isDebugEnabled()) {
                log.debug("Configured structured response format for {} with schema name '{}', maxCompletionTokens={}",
                        questionType, jsonSchema.getName(), maxCompletionTokens);
            }

            return options;

        } catch (Exception e) {
            log.error("Failed to build JSON schema response format for {}", questionType);
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
                        continue;
                    }
                    
                    questions.add(question);
                } catch (Exception e) {
                    warnings.add("Failed to parse question: INVALID_STRUCTURE");
                    log.warn("Rejected malformed structured question");
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
            log.error("Rejected structured response with invalid JSON");
            
            // Check if this is a truncation error (EOF while parsing)
            if (e.getMessage() != null && 
                (e.getMessage().contains("end-of-input") || 
                 e.getMessage().contains("Unexpected end") ||
                 e.getMessage().contains("EOF"))) {
                log.error("JSON response appears to be truncated. This may indicate:");
                log.error("  1. max-completion-tokens ({}) is too high and hit model's hard limit", maxCompletionTokens);
                log.error("  2. Question count ({}) is too large for the configured token limit", expectedType);
                log.error("  3. Content complexity requires fewer questions or higher token limit");
                throw new AIResponseParseException(
                    "JSON response truncated due to token limit. " +
                    "Current max-completion-tokens: " + maxCompletionTokens + ". " +
                    "Try reducing question count or increasing max-completion-tokens in configuration.");
            }
            
            throw new AIResponseParseException("Invalid JSON in structured response");
        }
    }

    private void retainRequestedDifficulty(
            StructuredQuestionResponse response,
            Difficulty expectedDifficulty) {
        List<StructuredQuestion> matchingQuestions = new ArrayList<>();

        for (StructuredQuestion question : response.getQuestions()) {
            if (question.getDifficulty() != expectedDifficulty) {
                response.getWarnings().add("Question difficulty mismatch: expected "
                        + expectedDifficulty + " but got " + question.getDifficulty());
                continue;
            }
            matchingQuestions.add(question);
        }

        if (matchingQuestions.isEmpty()) {
            throw new AIResponseParseException(
                    "No questions matched requested difficulty " + expectedDifficulty);
        }

        response.setQuestions(matchingQuestions);
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

        JsonNode confidenceNode = questionNode.get("confidence");
        if (!confidenceNode.isNumber()) {
            throw new AIResponseParseException("Question confidence must be numeric");
        }
        double confidence = confidenceNode.doubleValue();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new AIResponseParseException(
                    "Question confidence must be a finite number between 0.0 and 1.0");
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
            throw new AIResponseParseException("Failed to serialize structured question content");
        }
        
        // Now required fields (strict mode)
        builder.hint(questionNode.get("hint").asText());
        builder.explanation(questionNode.get("explanation").asText());
        builder.confidence(confidence);
        
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
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(
                contentNode,
                FillGapContentValidator.ValidationMode.STRICT_AI);
        if (!result.valid()) {
            throw new AIResponseParseException("AI-generated FILL_GAP validation failed: " + result.errorMessage());
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
    private boolean isRateLimitError(Throwable e) {
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

    private RetryDecision determineRetry(Exception failure, int retryCount) {
        Optional<AiProviderHttpException> providerFailure = findCause(
                failure,
                AiProviderHttpException.class
        );
        if (providerFailure.isPresent()) {
            AiProviderHttpException typedFailure = providerFailure.get();
            if (!typedFailure.failureKind().retryable()) {
                return RetryDecision.stop(GenerationFailureCategory.PROVIDER_TERMINAL);
            }

            Optional<Duration> retryAfter = typedFailure.retryAfter();
            if (retryAfter.isPresent()) {
                Duration maxDelay = Duration.ofMillis(rateLimitConfig.getMaxDelayMs());
                if (retryAfter.get().compareTo(maxDelay) > 0) {
                    return RetryDecision.stop(GenerationFailureCategory.RETRY_DELAY_EXCEEDED);
                }
                long delayMs = calculateProviderRetryDelay(
                        retryCount,
                        retryAfter.get().toMillis()
                );
                return RetryDecision.retry(delayMs, classifyFailure(typedFailure));
            }
            return RetryDecision.retry(
                    calculateBackoffDelay(retryCount),
                    classifyFailure(typedFailure)
            );
        }

        if (findCause(failure, NonTransientAiException.class).isPresent()) {
            return RetryDecision.stop(GenerationFailureCategory.PROVIDER_TERMINAL);
        }
        if (findCause(failure, TransientAiException.class).isPresent()
                || findCause(failure, ResourceAccessException.class).isPresent()
                || isRateLimitError(failure)) {
            return RetryDecision.retry(
                    calculateBackoffDelay(retryCount),
                    classifyFailure(failure)
            );
        }
        if (Thread.currentThread().isInterrupted()) {
            return RetryDecision.stop(GenerationFailureCategory.INTERRUPTED);
        }
        return RetryDecision.retry(0, classifyFailure(failure));
    }

    private GenerationFailureCategory classifyFailure(Throwable failure) {
        if (failure instanceof ProviderAttemptBudgetExhaustedException) {
            return GenerationFailureCategory.ATTEMPT_BUDGET_EXHAUSTED;
        }
        Optional<AiProviderHttpException> providerFailure = findCause(
                failure,
                AiProviderHttpException.class
        );
        if (providerFailure.isPresent()) {
            AiProviderHttpException typedFailure = providerFailure.get();
            return switch (typedFailure.failureKind()) {
                case RATE_LIMIT -> GenerationFailureCategory.RATE_LIMIT;
                case QUOTA_EXHAUSTED, CLIENT_ERROR -> GenerationFailureCategory.PROVIDER_TERMINAL;
                case REQUEST_TIMEOUT, CONFLICT, SERVER_ERROR -> GenerationFailureCategory.PROVIDER_RETRYABLE;
            };
        }
        if (findCause(failure, NonTransientAiException.class).isPresent()) {
            return GenerationFailureCategory.PROVIDER_TERMINAL;
        }
        if (findCause(failure, TransientAiException.class).isPresent()
                || findCause(failure, ResourceAccessException.class).isPresent()) {
            return GenerationFailureCategory.PROVIDER_RETRYABLE;
        }
        if (isRateLimitError(failure)) {
            return GenerationFailureCategory.RATE_LIMIT;
        }
        if (failure instanceof AIResponseParseException) {
            return GenerationFailureCategory.INVALID_RESPONSE;
        }
        if (failure instanceof PromptConstructionException) {
            return GenerationFailureCategory.PROMPT_CONSTRUCTION;
        }
        if (failure instanceof AiServiceException) {
            return Thread.currentThread().isInterrupted()
                    ? GenerationFailureCategory.INTERRUPTED
                    : GenerationFailureCategory.AI_SERVICE;
        }
        return GenerationFailureCategory.PROVIDER_FAILURE;
    }

    private <T extends Throwable> Optional<T> findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 10) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause();
            depth++;
        }
        return Optional.empty();
    }

    private String safeFailureSummary(
            Exception failure,
            GenerationFailureCategory failureCategory
    ) {
        if (failure instanceof AIResponseParseException) {
            return failure.getMessage();
        }
        return failureCategory.name();
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

    private long calculateProviderRetryDelay(int retryCount, long providerMinimumMs) {
        long maxDelayMs = rateLimitConfig.getMaxDelayMs();
        long normalBackoffMs = calculateBackoffDelay(retryCount);
        long headroomMs = maxDelayMs - providerMinimumMs;
        long jitterCapMs = Math.min(
                headroomMs,
                Math.max(0L, (long) (rateLimitConfig.getBaseDelayMs()
                        * rateLimitConfig.getJitterFactor()))
        );
        long positiveJitterMs = jitterCapMs > 0
                ? ThreadLocalRandom.current().nextLong(jitterCapMs + 1)
                : 0;

        return Math.min(
                maxDelayMs,
                Math.max(normalBackoffMs, providerMinimumMs + positiveJitterMs)
        );
    }

    private boolean waitForRetry(long delayMs, Supplier<Boolean> cancellationChecker) {
        if (cancellationChecker == null) {
            sleepForRateLimit(delayMs);
            return true;
        }

        long remainingDelayMs = delayMs;
        while (remainingDelayMs > 0) {
            if (isCancellationRequested(cancellationChecker)) {
                return false;
            }

            long waitSliceMs = Math.min(
                    remainingDelayMs,
                    CANCELLATION_CHECK_INTERVAL_MS
            );
            sleepForRateLimit(waitSliceMs);
            remainingDelayMs -= waitSliceMs;
        }

        return !isCancellationRequested(cancellationChecker);
    }

    private boolean isCancellationRequested(Supplier<Boolean> cancellationChecker) {
        return cancellationChecker != null && Boolean.TRUE.equals(cancellationChecker.get());
    }

    private StructuredQuestionResponse cancelledResponse() {
        return StructuredQuestionResponse.builder()
                .questions(List.of())
                .warnings(List.of("Generation cancelled by user"))
                .tokensUsed(0L)
                .build();
    }
    
    /**
     * Sleep for rate limit delay
     */
    void sleepForRateLimit(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting for rate limit", ie);
        }
    }

    private enum GenerationFailureCategory {
        ATTEMPT_BUDGET_EXHAUSTED,
        RATE_LIMIT,
        PROVIDER_RETRYABLE,
        PROVIDER_TERMINAL,
        RETRY_DELAY_EXCEEDED,
        INVALID_RESPONSE,
        PROMPT_CONSTRUCTION,
        AI_SERVICE,
        INTERRUPTED,
        PROVIDER_FAILURE
    }

    private record RetryDecision(
            boolean retry,
            long delayMs,
            GenerationFailureCategory failureCategory
    ) {

        private static RetryDecision retry(
                long delayMs,
                GenerationFailureCategory failureCategory
        ) {
            return new RetryDecision(true, delayMs, failureCategory);
        }

        private static RetryDecision stop(GenerationFailureCategory failureCategory) {
            return new RetryDecision(false, 0, failureCategory);
        }
    }

    private static final class PromptConstructionException extends RuntimeException {

        private PromptConstructionException() {
            super("Prompt construction failed");
        }
    }
}
