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
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;

import java.util.*;

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
        if (chunks.isEmpty()) {
            log.debug("No chunks matched scope for document {}. Returning zeroed estimate.", documentId);
            long llmZero = 0L;
            long billingZero = llmTokensToBillingTokens(llmZero);
            UUID estimationId = UUID.randomUUID();
            String humanizedEstimate = EstimationDto.createHumanizedEstimate(llmZero, billingZero, billingProperties.getCurrency());
            return new EstimationDto(llmZero, billingZero, null, billingProperties.getCurrency(), true, humanizedEstimate, estimationId);
        }

        // Pre-compute prompt overhead tokens (system + context)
        long systemTokens = estimateTokens(promptTemplateService.buildSystemPrompt());
        long contextTokens = estimateTokens(safeLoadTemplate("base/context-template.txt"));

        // Pre-compute question-type template tokens used in this request
        Map<QuestionType, Long> templateTokensByType = new EnumMap<>(QuestionType.class);
        for (QuestionType type : request.questionsPerType().keySet()) {
            String templateName = "question-types/" + getQuestionTypeTemplateName(type);
            long templateTokens = estimateTokens(safeLoadTemplate(templateName));
            templateTokensByType.put(type, templateTokens);
        }

        long totalLlmTokens = 0L;

        for (DocumentChunk chunk : chunks) {
            int charCount = chunk.getCharacterCount() != null ? chunk.getCharacterCount() :
                    (chunk.getContent() != null ? chunk.getContent().length() : 0);
            long contentTokens = estimateTokens(charCount);

            for (Map.Entry<QuestionType, Integer> e : request.questionsPerType().entrySet()) {
                QuestionType type = e.getKey();
                int count = Optional.ofNullable(e.getValue()).orElse(0);
                if (count <= 0) continue; // defensive

                long inputTokens = systemTokens + contextTokens + templateTokensByType.getOrDefault(type, 0L) + contentTokens;
                double difficultyMultiplier = getDifficultyMultiplier(request.difficulty());
                long completionTokens = (long) Math.ceil(count * COMPLETION_TOKENS_PER_QUESTION.getOrDefault(type, 120) * difficultyMultiplier);

                totalLlmTokens += inputTokens + completionTokens;
            }
        }

        // Apply safety factor to LLM tokens
        double safetyFactor = billingProperties.getSafetyFactor();
        long adjustedLlm = (long) Math.ceil(totalLlmTokens * safetyFactor);

        long billingTokens = llmTokensToBillingTokens(adjustedLlm);

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
}
