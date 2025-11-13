package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.impl.EstimationServiceImpl;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EstimationServiceTest {

    private DocumentRepository documentRepository;
    private DocumentChunkRepository documentChunkRepository;
    private PromptTemplateService promptTemplateService;
    private BillingProperties billingProperties;
    private EstimationServiceImpl estimationService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentChunkRepository = mock(DocumentChunkRepository.class);
        promptTemplateService = mock(PromptTemplateService.class);
        billingProperties = new BillingProperties();
        // Defaults: ratio=1000, safety=1.2, currency=usd
        estimationService = new EstimationServiceImpl(billingProperties, documentRepository, documentChunkRepository, promptTemplateService);

        // Stub template loads to fixed lengths → predictable token counts (length/4)
        when(promptTemplateService.buildSystemPrompt()).thenReturn("x".repeat(100)); // 25 tokens
        when(promptTemplateService.loadPromptTemplate("base/context-template.txt")).thenReturn("y".repeat(100)); // 25 tokens
        // question type templates → 25 tokens each
        when(promptTemplateService.loadPromptTemplate(startsWith("question-types/"))).thenReturn("z".repeat(100));
    }

    private Document makeDocumentWithChunks(int numChunks, int charCountPerChunk) {
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            DocumentChunk c = new DocumentChunk();
            c.setChunkIndex(i);
            c.setCharacterCount(charCountPerChunk);
            chunks.add(c);
        }
        doc.setChunks(chunks);
        doc.setTotalChunks(numChunks);
        doc.setFileSize((long) numChunks * charCountPerChunk);
        return doc;
    }

    private GenerateQuizFromDocumentRequest requestFor(Map<QuestionType, Integer> qpt) {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                null, null,
                qpt,
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );
    }

    @Test
    void estimateQuizGeneration_appliesSafetyAndCeilConversion() {
        // 1 chunk, content 100 chars → 25 tokens
        // system(25) + context(25) + template(25) + content(25) = 100 input tokens
        // TRUE_FALSE completion ~ 60 per question; with 1 question → +60
        // total per-chunk/type = 160 LLM tokens
        // safety 1.2 → 192; billing ratio 1000 → ceil(192/1000) = 1

        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);

        EstimationDto out = estimationService.estimateQuizGeneration(docId, req);

        assertEquals(192, out.estimatedLlmTokens());
        assertEquals(1, out.estimatedBillingTokens());
        assertTrue(out.estimate());
        assertEquals(billingProperties.getCurrency(), out.currency());
    }

    @Test
    void estimateQuizGeneration_scalesWithChunkCount() {
        // With 1 chunk → 160 LLM (before safety). With 2 chunks → 320 LLM (before safety).
        // After safety (1.2): 192 vs 384

        // First doc with 1 chunk
        Document doc1 = makeDocumentWithChunks(1, 100);
        UUID docId1 = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId1)).thenReturn(Optional.of(doc1));

        // Second doc with 2 chunks
        Document doc2 = makeDocumentWithChunks(2, 100);
        UUID docId2 = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId2)).thenReturn(Optional.of(doc2));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);

        EstimationDto one = estimationService.estimateQuizGeneration(docId1, req);
        EstimationDto two = estimationService.estimateQuizGeneration(docId2, req);

        assertEquals(192, one.estimatedLlmTokens());
        assertEquals(384, two.estimatedLlmTokens());
        // Billing tokens both round up by ratio
        assertEquals(1, one.estimatedBillingTokens());
        assertEquals(1, two.estimatedBillingTokens());
    }

    @Test
    void estimateQuizGeneration_reflectsDifficultyMultiplier() {
        // Using same doc and request shape, HARD should yield more LLM tokens than MEDIUM

        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);

        var reqMedium = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                null, null, qpt, Difficulty.MEDIUM, 2, null, List.of()
        );
        var reqHard = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                null, null, qpt, Difficulty.HARD, 2, null, List.of()
        );

        EstimationDto med = estimationService.estimateQuizGeneration(docId, reqMedium);
        EstimationDto hard = estimationService.estimateQuizGeneration(docId, reqHard);

        assertTrue(hard.estimatedLlmTokens() > med.estimatedLlmTokens(),
                "HARD difficulty should result in higher LLM tokens than MEDIUM");
    }

    @Test
    void llmTokensToBillingTokens_ceilBoundaries() {
        // Default ratio is 1000
        assertEquals(0, estimationService.llmTokensToBillingTokens(0));
        assertEquals(1, estimationService.llmTokensToBillingTokens(1));
        assertEquals(1, estimationService.llmTokensToBillingTokens(999));
        assertEquals(1, estimationService.llmTokensToBillingTokens(1000));
        assertEquals(2, estimationService.llmTokensToBillingTokens(1001));
    }

    @Test
    void estimateQuizGeneration_returnsZeroWhenNoChunksMatchScope() {
        // Document with chunks indices 0..2
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DocumentChunk c = new DocumentChunk();
            c.setChunkIndex(i);
            c.setCharacterCount(100);
            chunks.add(c);
        }
        doc.setChunks(chunks);

        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        // Request indices that do not exist
        var req = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.SPECIFIC_CHUNKS, List.of(10, 11),
                null, null, null, null,
                Map.of(QuestionType.TRUE_FALSE, 1),
                Difficulty.MEDIUM, 2, null, List.of()
        );

        EstimationDto out = estimationService.estimateQuizGeneration(docId, req);
        assertEquals(0, out.estimatedLlmTokens());
        assertEquals(0, out.estimatedBillingTokens());
        assertTrue(out.estimate());
        assertEquals(billingProperties.getCurrency(), out.currency());
    }

    @Test
    void estimateQuizGeneration_scopeSpecificChunks() {
        // 3 chunks, only 2 selected → linear scale
        Document doc = makeDocumentWithChunks(3, 100); // each chunk ~160 LLM before safety
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);

        var reqAll = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.ENTIRE_DOCUMENT, null,
                null, null, null, null,
                qpt, Difficulty.MEDIUM, 2, null, List.of()
        );
        var reqSome = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.SPECIFIC_CHUNKS, List.of(0, 1),
                null, null, null, null,
                qpt, Difficulty.MEDIUM, 2, null, List.of()
        );

        EstimationDto all = estimationService.estimateQuizGeneration(docId, reqAll);
        EstimationDto some = estimationService.estimateQuizGeneration(docId, reqSome);

        // ENTIRE_DOCUMENT with 3 chunks: 3 * 192 = 576; SPECIFIC_CHUNKS with 2 chunks: 384
        assertEquals(576, all.estimatedLlmTokens());
        assertEquals(384, some.estimatedLlmTokens());
    }

    @Test
    void estimateQuizGeneration_scopeChapterAndSection() {
        // Create chunks with chapter/section metadata
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        List<DocumentChunk> chunks = new ArrayList<>();

        DocumentChunk c1 = new DocumentChunk();
        c1.setChunkIndex(0); c1.setCharacterCount(100); c1.setChapterTitle("Intro"); c1.setSectionTitle("Basics");
        DocumentChunk c2 = new DocumentChunk();
        c2.setChunkIndex(1); c2.setCharacterCount(100); c2.setChapterTitle("Intro"); c2.setSectionTitle("Advanced");
        DocumentChunk c3 = new DocumentChunk();
        c3.setChunkIndex(2); c3.setCharacterCount(100); c3.setChapterTitle("Other"); c3.setSectionTitle("Basics");
        chunks.add(c1); chunks.add(c2); chunks.add(c3);
        doc.setChunks(chunks);

        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);

        // Chapter scope (Intro) should pick c1 and c2 → 2 chunks → 384 LLM tokens after safety
        var reqChapter = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.SPECIFIC_CHAPTER, null,
                "Intro", null, null, null,
                qpt, Difficulty.MEDIUM, 2, null, List.of()
        );

        // Section scope (ambiguous mapping: uses chapterTitle field to pass sectionTitle)
        // pick chunks with sectionTitle == "Basics" → c1 and c3 → 2 chunks → 384
        var reqSection = new GenerateQuizFromDocumentRequest(
                docId, QuizScope.SPECIFIC_SECTION, null,
                "Basics", null, null, null,
                qpt, Difficulty.MEDIUM, 2, null, List.of()
        );

        EstimationDto ch = estimationService.estimateQuizGeneration(docId, reqChapter);
        EstimationDto sec = estimationService.estimateQuizGeneration(docId, reqSection);

        assertEquals(384, ch.estimatedLlmTokens());
        assertEquals(384, sec.estimatedLlmTokens());
    }

    @Test
    void estimateQuizGeneration_includesHumanizedEstimate() {
        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);

        EstimationDto result = estimationService.estimateQuizGeneration(docId, req);

        assertNotNull(result.humanizedEstimate());
        assertTrue(result.humanizedEstimate().contains("1 billing token"));
        assertTrue(result.humanizedEstimate().contains("192 LLM tokens"));
    }

    @Test
    void estimateQuizGeneration_includesEstimationId() {
        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);

        EstimationDto result = estimationService.estimateQuizGeneration(docId, req);

        assertNotNull(result.estimationId());
        assertTrue(result.estimationId().toString().length() > 0);
    }

    @Test
    void estimateQuizGeneration_deterministicForSameInputs() {
        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);

        EstimationDto result1 = estimationService.estimateQuizGeneration(docId, req);
        EstimationDto result2 = estimationService.estimateQuizGeneration(docId, req);

        // LLM and billing tokens should be identical
        assertEquals(result1.estimatedLlmTokens(), result2.estimatedLlmTokens());
        assertEquals(result1.estimatedBillingTokens(), result2.estimatedBillingTokens());
        assertEquals(result1.humanizedEstimate(), result2.humanizedEstimate());
        
        // Only estimationId should be different (unique per call)
        assertNotEquals(result1.estimationId(), result2.estimationId());
    }

    @Test
    void estimateQuizGeneration_zeroTokensHumanizedEstimate() {
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setChunks(List.of()); // No chunks - this should trigger the zero estimate path

        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);
        // Set scope to SPECIFIC_CHUNKS with empty indices to trigger zero estimate
        req = new GenerateQuizFromDocumentRequest(
                docId,
                QuizScope.SPECIFIC_CHUNKS,
                List.of(), // Empty chunk indices
                null, null, req.quizTitle(), req.quizDescription(),
                req.questionsPerType(), req.difficulty(), req.estimatedTimePerQuestion(),
                req.categoryId(), req.tagIds()
        );

        EstimationDto result = estimationService.estimateQuizGeneration(docId, req);

        assertEquals("No tokens required", result.humanizedEstimate());
        assertEquals(0, result.estimatedLlmTokens());
        assertEquals(0, result.estimatedBillingTokens());
    }

    @Test
    void estimateQuizGeneration_multipleQuestionTypes() {
        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(
                QuestionType.MCQ_SINGLE, 2,
                QuestionType.OPEN, 1,
                QuestionType.TRUE_FALSE, 3
        );
        var req = requestFor(qpt);

        EstimationDto result = estimationService.estimateQuizGeneration(docId, req);

        // Should be higher than single question type due to multiple types
        assertTrue(result.estimatedLlmTokens() > 192); // Single TRUE_FALSE baseline
        assertTrue(result.estimatedBillingTokens() >= 1);
    }

    @Test
    void estimateQuizGeneration_safetyFactorApplied() {
        Document doc = makeDocumentWithChunks(1, 100);
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdWithChunks(docId)).thenReturn(Optional.of(doc));

        Map<QuestionType, Integer> qpt = Map.of(QuestionType.TRUE_FALSE, 1);
        var req = requestFor(qpt);

        EstimationDto result = estimationService.estimateQuizGeneration(docId, req);

        // Base calculation: 100 input + 60 completion = 160
        // With safety factor 1.2: 160 * 1.2 = 192
        assertEquals(192, result.estimatedLlmTokens());
    }
}
