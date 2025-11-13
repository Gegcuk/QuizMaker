package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
public class EstimationServiceImpl implements EstimationService {

    private final BillingProperties billingProperties;
    private final DocumentRepository documentRepository;
    private final PromptTemplateService promptTemplateService;

    // Conservative average completion tokens per question output by type (rough heuristic)
    private static final Map<QuestionType, Integer> COMPLETION_TOKENS_PER_QUESTION;
    private static final int DEFAULT_CHUNK_CHARACTER_SIZE = 50000;
    static {
        Map<QuestionType, Integer> m = new EnumMap<>(QuestionType.class);
        m.put(QuestionType.MCQ_SINGLE, 120);
        m.put(QuestionType.MCQ_MULTI, 140);
        m.put(QuestionType.TRUE_FALSE, 60);
        m.put(QuestionType.OPEN, 180);
        m.put(QuestionType.FILL_GAP, 120);
        m.put(QuestionType.ORDERING, 140);
        m.put(QuestionType.COMPLIANCE, 160);
        m.put(QuestionType.HOTSPOT, 160);
        m.put(QuestionType.MATCHING, 160);
        COMPLETION_TOKENS_PER_QUESTION = Collections.unmodifiableMap(m);
    }

    @Override
    @Transactional(readOnly = true)
    public EstimationDto estimateQuizGeneration(UUID documentId, GenerateQuizFromDocumentRequest request) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Document document = documentRepository.findByIdWithChunks(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));

        List<DocumentChunk> chunks = selectChunks(document, request);
        log.info("Found {} chunks for document {} with scope {}", chunks.size(), documentId, request.quizScope());
        
        if (chunks.isEmpty()) {
            // If ENTIRE_DOCUMENT requested but chunks are empty, calculate estimate based on document content
            if (request.quizScope() == null || request.quizScope() == QuizScope.ENTIRE_DOCUMENT) {
                log.warn("No chunks matched ENTIRE_DOCUMENT for {}. Calculating estimate from document content.", documentId);
                
                // Calculate estimate based on document content directly
                long estimatedLlmTokens = calculateEstimateFromDocumentContent(document, request);
                long estimatedBillingTokens = Math.max(1L, llmTokensToBillingTokens(estimatedLlmTokens));
                
                log.info("Fallback calculation result: {} LLM tokens -> {} billing tokens", estimatedLlmTokens, estimatedBillingTokens);
                
                UUID estimationId = UUID.randomUUID();
                String humanizedEstimate = EstimationDto.createHumanizedEstimate(estimatedLlmTokens, estimatedBillingTokens, billingProperties.getCurrency());
                return new EstimationDto(estimatedLlmTokens, estimatedBillingTokens, null, billingProperties.getCurrency(), true, humanizedEstimate, estimationId);
            }

            // For scopes that explicitly select nothing (e.g., SPECIFIC_CHUNKS with empty indices),
            // keep returning a zeroed estimate as before.
            long llmZero = 0L;
            long billingZero = llmTokensToBillingTokens(llmZero);
            UUID estimationId = UUID.randomUUID();
            String humanizedEstimate = EstimationDto.createHumanizedEstimate(llmZero, billingZero, billingProperties.getCurrency());
            return new EstimationDto(llmZero, billingZero, null, billingProperties.getCurrency(), true, humanizedEstimate, estimationId);
        }

        long chunkCount = chunks.size();
        long requestedQuestionTypes = countRequestedQuestionTypes(request.questionsPerType());
        if (requestedQuestionTypes == 0) {
            log.warn("No question types with positive counts found in request {}. Returning zero estimate.", request);
            long llmZero = 0L;
            long billingZero = llmTokensToBillingTokens(llmZero);
            UUID estimationId = UUID.randomUUID();
            String humanizedEstimate = EstimationDto.createHumanizedEstimate(llmZero, billingZero, billingProperties.getCurrency());
            return new EstimationDto(llmZero, billingZero, null, billingProperties.getCurrency(), true, humanizedEstimate, estimationId);
        }

        int representativeChunkSize = resolveRepresentativeChunkSize(chunks);
        long tokensPerCall = Math.max(1L, estimateTokens(representativeChunkSize));
        long totalCalls = chunkCount * requestedQuestionTypes;
        double difficultyMultiplier = getDifficultyMultiplier(request.difficulty());
        long totalLlmTokens = (long) Math.max(1L, Math.ceil(totalCalls * tokensPerCall * difficultyMultiplier));

        log.info("Heuristic estimation inputs -> chunks: {}, questionTypes: {}, chunkSize(chars): {}, tokensPerCall: {}, totalCalls: {}, difficultyMultiplier: {}",
                chunkCount, requestedQuestionTypes, representativeChunkSize, tokensPerCall, totalCalls, difficultyMultiplier);

        double safetyFactor = billingProperties.getSafetyFactor();
        long adjustedLlm = (long) Math.ceil(totalLlmTokens * safetyFactor);

        long billingTokens = llmTokensToBillingTokens(adjustedLlm);
        
        log.info("Heuristic estimation result: {} LLM tokens -> {} billing tokens (safety factor: {})", 
                adjustedLlm, billingTokens, safetyFactor);

        UUID estimationId = UUID.randomUUID();
        String humanizedEstimate = EstimationDto.createHumanizedEstimate(adjustedLlm, billingTokens, billingProperties.getCurrency());
        
        return new EstimationDto(
                adjustedLlm,
                billingTokens,
                null, // approxCostCents not implemented in MVP
                billingProperties.getCurrency(),
                true, // explicitly an estimate, not a quote
                humanizedEstimate,
                estimationId
        );
    }

    @Override
    public long llmTokensToBillingTokens(long llmTokens) {
        long ratio = Math.max(1L, billingProperties.getTokenToLlmRatio());
        return (long) Math.ceil((double) llmTokens / (double) ratio);
    }

    private List<DocumentChunk> selectChunks(Document document, GenerateQuizFromDocumentRequest request) {
        List<DocumentChunk> allChunks = document.getChunks() != null ? document.getChunks() : List.of();
        QuizScope scope = request.quizScope() == null ? QuizScope.ENTIRE_DOCUMENT : request.quizScope();

        return switch (scope) {
            case SPECIFIC_CHUNKS -> {
                List<Integer> indices = request.chunkIndices();
                if (indices == null || indices.isEmpty()) yield List.of();
                yield allChunks.stream()
                        .filter(c -> indices.contains(c.getChunkIndex()))
                        .toList();
            }
            case SPECIFIC_CHAPTER -> allChunks.stream()
                    .filter(c -> matchesChapter(c, request.chapterTitle(), request.chapterNumber()))
                    .toList();
            case SPECIFIC_SECTION -> allChunks.stream()
                    .filter(c -> matchesSection(c, request.chapterTitle(), request.chapterNumber()))
                    .toList();
            case ENTIRE_DOCUMENT -> allChunks;
        };
    }

    private boolean matchesChapter(DocumentChunk chunk, String chapterTitle, Integer chapterNumber) {
        if (chapterTitle != null && chunk.getChapterTitle() != null) {
            return chunk.getChapterTitle().equalsIgnoreCase(chapterTitle);
        }
        if (chapterNumber != null && chunk.getChapterNumber() != null) {
            return chunk.getChapterNumber().equals(chapterNumber);
        }
        return false;
    }

    private boolean matchesSection(DocumentChunk chunk, String sectionTitle, Integer sectionNumber) {
        if (sectionTitle != null && chunk.getSectionTitle() != null) {
            return chunk.getSectionTitle().equalsIgnoreCase(sectionTitle);
        }
        if (sectionNumber != null && chunk.getSectionNumber() != null) {
            return chunk.getSectionNumber().equals(sectionNumber);
        }
        return false;
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0L;
        return estimateTokens(text.length());
    }

    private long estimateTokens(int charCount) {
        // Rough heuristic: ~4 chars per token in English; ceil to avoid under-estimation.
        // TODO: Phase 3+ - Add schema overhead for structured output
        //  With structured output (Phase 3), JSON schema is appended to system prompt per request.
        //  Schema size varies by question type (~300-800 tokens per type).
        //  Consider adding: schemaOverhead = questionTypes.size() * AVG_SCHEMA_TOKENS_PER_TYPE
        //  where AVG_SCHEMA_TOKENS_PER_TYPE ≈ 500 tokens (measured from QuestionSchemaRegistry).
        //  This will prevent under-estimation and billing surprises.
        return (long) Math.ceil(charCount / 4.0);
    }

    private String safeLoadTemplate(String templatePath) {
        try {
            return promptTemplateService.loadPromptTemplate(templatePath);
        } catch (Exception e) {
            log.warn("Failed to load template {} for estimation, defaulting to empty: {}", templatePath, e.getMessage());
            return "";
        }
    }

    private String getQuestionTypeTemplateName(QuestionType questionType) {
        return switch (questionType) {
            case MCQ_SINGLE -> "mcq-single.txt";
            case MCQ_MULTI -> "mcq-multi.txt";
            case TRUE_FALSE -> "true-false.txt";
            case OPEN -> "open-question.txt";
            case FILL_GAP -> "fill-gap.txt";
            case ORDERING -> "ordering.txt";
            case COMPLIANCE -> "compliance.txt";
            case HOTSPOT -> "hotspot.txt";
            case MATCHING -> "matching.txt";
        };
    }

    private double getDifficultyMultiplier(Difficulty difficulty) {
        if (difficulty == null) return 1.0d;
        return switch (difficulty) {
            case EASY -> 0.9d;
            case MEDIUM -> 1.0d;
            case HARD -> 1.15d;
        };
    }

    /**
     * Calculate token estimate when chunks are missing by using document content directly.
     * This is a fallback for when document processing didn't create proper chunks.
     */
    private long calculateEstimateFromDocumentContent(Document document, GenerateQuizFromDocumentRequest request) {
        // Get document content from chunks if available, otherwise use filename as fallback
        String content = "";
        
        // Try to get content from chunks first
        if (document.getChunks() != null && !document.getChunks().isEmpty()) {
            content = document.getChunks().stream()
                    .map(DocumentChunk::getContent)
                    .filter(c -> c != null && !c.trim().isEmpty())
                    .reduce("", (a, b) -> a + " " + b)
                    .trim();
        }
        
        // Fallback: use filename if no chunk content available
        if (content.trim().isEmpty()) {
            content = document.getOriginalFilename() != null ? document.getOriginalFilename() : "";
        }
        
        if (content.trim().isEmpty()) {
            log.warn("Document {} has no content available for estimation. Using minimal estimate.", document.getId());
            return 1000L; // Minimal reasonable estimate
        }
        
        log.info("Calculating estimate from document content: {} characters", content.length());
        log.info("Question types and counts: {}", request.questionsPerType());
        log.info("Difficulty: {}", request.difficulty());
        
        // Pre-compute prompt overhead tokens (system + context)
        long systemTokens = estimateTokens(promptTemplateService.buildSystemPrompt());
        long contextTokens = estimateTokens(safeLoadTemplate("base/context-template.txt"));
        
        log.info("System tokens: {}, Context tokens: {}", systemTokens, contextTokens);
        
        // Pre-compute question-type template tokens used in this request
        Map<QuestionType, Long> templateTokensByType = new EnumMap<>(QuestionType.class);
        for (QuestionType type : request.questionsPerType().keySet()) {
            String templateName = "question-types/" + getQuestionTypeTemplateName(type);
            long templateTokens = estimateTokens(safeLoadTemplate(templateName));
            templateTokensByType.put(type, templateTokens);
        }
        
        long contentTokens = estimateTokens(content);
        long totalLlmTokens = 0L;
        
        log.info("Content tokens: {}", contentTokens);
        
        // Calculate tokens for each question type
        for (Map.Entry<QuestionType, Integer> e : request.questionsPerType().entrySet()) {
            QuestionType type = e.getKey();
            int count = Optional.ofNullable(e.getValue()).orElse(0);
            if (count <= 0) continue;
            
            long templateTokens = templateTokensByType.getOrDefault(type, 0L);
            long inputTokens = systemTokens + contextTokens + templateTokens + contentTokens;
            double difficultyMultiplier = getDifficultyMultiplier(request.difficulty());
            long completionTokens = (long) Math.ceil(count * COMPLETION_TOKENS_PER_QUESTION.getOrDefault(type, 120) * difficultyMultiplier);
            
            log.info("Question type {}: count={}, templateTokens={}, inputTokens={}, completionTokens={}, difficultyMultiplier={}", 
                    type, count, templateTokens, inputTokens, completionTokens, difficultyMultiplier);
            
            totalLlmTokens += inputTokens + completionTokens;
        }
        
        // Apply safety factor to LLM tokens
        double safetyFactor = billingProperties.getSafetyFactor();
        long adjustedLlm = (long) Math.ceil(totalLlmTokens * safetyFactor);
        
        log.info("Total LLM tokens before safety factor: {}", totalLlmTokens);
        log.info("Safety factor: {}", safetyFactor);
        log.info("Calculated estimate from document content: {} LLM tokens", adjustedLlm);
        return adjustedLlm;
    }

    private long countRequestedQuestionTypes(Map<QuestionType, Integer> questionsPerType) {
        if (questionsPerType == null || questionsPerType.isEmpty()) {
            return 0L;
        }
        return questionsPerType.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .count();
    }

    private int resolveRepresentativeChunkSize(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return DEFAULT_CHUNK_CHARACTER_SIZE;
        }

        return chunks.stream()
                .map(DocumentChunk::getCharacterCount)
                .filter(Objects::nonNull)
                .filter(size -> size > 0)
                .max(Integer::compareTo)
                .orElse(DEFAULT_CHUNK_CHARACTER_SIZE);
    }

    @Override
    public long computeActualBillingTokens(List<Question> questions, Difficulty difficulty, long inputPromptTokens) {
        if (questions == null || questions.isEmpty()) {
            log.warn("No questions provided for actual token computation, returning input prompt tokens only");
            return llmTokensToBillingTokens(inputPromptTokens);
        }

        log.info("Computing actual billing tokens for {} questions with difficulty {}", questions.size(), difficulty);

        // Group questions by type to compute EOT (Expected Output Tokens)
        Map<QuestionType, Long> questionsByType = questions.stream()
                .collect(Collectors.groupingBy(
                        Question::getType,
                        Collectors.counting()
                ));

        double difficultyMultiplier = getDifficultyMultiplier(difficulty);
        long totalOutputTokens = 0L;

        // Compute output tokens using the same EOT table as estimation
        for (Map.Entry<QuestionType, Long> entry : questionsByType.entrySet()) {
            QuestionType type = entry.getKey();
            long count = entry.getValue();
            
            // Get base completion tokens for this question type
            int baseCompletionTokens = COMPLETION_TOKENS_PER_QUESTION.getOrDefault(type, 120);
            
            // Apply difficulty multiplier
            long adjustedCompletionTokens = (long) Math.ceil(baseCompletionTokens * difficultyMultiplier);
            
            // Total output tokens for this type
            long typeOutputTokens = count * adjustedCompletionTokens;
            totalOutputTokens += typeOutputTokens;
        }

        // Total LLM tokens = input prompt tokens + output tokens
        long totalLlmTokens = inputPromptTokens + totalOutputTokens;
        
        // Convert to billing tokens (no safety factor for actual usage)
        long actualBillingTokens = llmTokensToBillingTokens(totalLlmTokens);
        
        log.info("Actual token computation: inputPromptTokens={}, outputTokens={}, totalLlmTokens={}, actualBillingTokens={}", 
                inputPromptTokens, totalOutputTokens, totalLlmTokens, actualBillingTokens);
        
        return actualBillingTokens;
    }
}
