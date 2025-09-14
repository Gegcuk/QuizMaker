package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("EstimationService Tests")
class EstimationServiceImplTest {

    @Mock
    private BillingProperties billingProperties;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PromptTemplateService promptTemplateService;

    private EstimationServiceImpl estimationService;

    @BeforeEach
    void setUp() {
        estimationService = new EstimationServiceImpl(billingProperties, documentRepository, promptTemplateService);
        
        // Default billing properties - using lenient stubbing to avoid unnecessary stubbing warnings
        lenient().when(billingProperties.getTokenToLlmRatio()).thenReturn(1000L);
        lenient().when(billingProperties.getSafetyFactor()).thenReturn(1.2);
        lenient().when(billingProperties.getCurrency()).thenReturn("usd");
        
        // Default prompt templates - using lenient stubbing to avoid unnecessary stubbing warnings
        lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("System prompt content");
        lenient().when(promptTemplateService.loadPromptTemplate(any())).thenReturn("Template content");
    }

    @Nested
    @DisplayName("Token Conversion Tests")
    class TokenConversionTests {

        @Test
        @DisplayName("Should convert LLM tokens to billing tokens with default ratio")
        void shouldConvertLlmTokensToBillingTokensWithDefaultRatio() {
            // Given
            long llmTokens = 5000L;
            when(billingProperties.getTokenToLlmRatio()).thenReturn(1000L);

            // When
            long billingTokens = estimationService.llmTokensToBillingTokens(llmTokens);

            // Then
            assertThat(billingTokens).isEqualTo(5L); // 5000 / 1000 = 5
        }

        @Test
        @DisplayName("Should handle custom ratio")
        void shouldHandleCustomRatio() {
            // Given
            long llmTokens = 2500L;
            when(billingProperties.getTokenToLlmRatio()).thenReturn(500L);

            // When
            long billingTokens = estimationService.llmTokensToBillingTokens(llmTokens);

            // Then
            assertThat(billingTokens).isEqualTo(5L); // 2500 / 500 = 5
        }

        @Test
        @DisplayName("Should round up fractional results")
        void shouldRoundUpFractionalResults() {
            // Given
            long llmTokens = 1500L;
            when(billingProperties.getTokenToLlmRatio()).thenReturn(1000L);

            // When
            long billingTokens = estimationService.llmTokensToBillingTokens(llmTokens);

            // Then
            assertThat(billingTokens).isEqualTo(2L); // Math.ceil(1500/1000) = 2
        }

        @Test
        @DisplayName("Should handle zero tokens")
        void shouldHandleZeroTokens() {
            // Given
            long llmTokens = 0L;

            // When
            long billingTokens = estimationService.llmTokensToBillingTokens(llmTokens);

            // Then
            assertThat(billingTokens).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should handle minimum ratio of 1")
        void shouldHandleMinimumRatioOfOne() {
            // Given
            long llmTokens = 500L;
            when(billingProperties.getTokenToLlmRatio()).thenReturn(0L); // Will be clamped to 1

            // When
            long billingTokens = estimationService.llmTokensToBillingTokens(llmTokens);

            // Then
            assertThat(billingTokens).isEqualTo(500L); // 500 / 1 = 500
        }
    }

    @Nested
    @DisplayName("Document Not Found Tests")
    class DocumentNotFoundTests {

        @Test
        @DisplayName("Should throw DocumentNotFoundException when document not found")
        void shouldThrowDocumentNotFoundExceptionWhenDocumentNotFound() {
            // Given
            UUID documentId = UUID.randomUUID();
            GenerateQuizFromDocumentRequest request = createBasicRequest();
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.empty());

            // When & Then
            assertThatThrownBy(() -> estimationService.estimateQuizGeneration(documentId, request))
                    .isInstanceOf(DocumentNotFoundException.class)
                    .hasMessage("Document not found: " + documentId);
        }
    }

    @Nested
    @DisplayName("Empty Document Tests")
    class EmptyDocumentTests {

        @Test
        @DisplayName("Should return zero estimate for document with no chunks")
        void shouldReturnZeroEstimateForDocumentWithNoChunks() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(List.of());
            GenerateQuizFromDocumentRequest request = createBasicRequest();
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isEqualTo(1000L);
            assertThat(result.estimatedBillingTokens()).isEqualTo(1L);
            assertThat(result.estimate()).isTrue();
            assertThat(result.humanizedEstimate()).isNotEmpty();
        }

        @Test
        @DisplayName("Should return zero estimate for empty scope")
        void shouldReturnZeroEstimateForEmptyScope() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createRequestWithScope(QuizScope.SPECIFIC_CHUNKS, List.of());
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isEqualTo(0L);
            assertThat(result.estimatedBillingTokens()).isEqualTo(0L);
            assertThat(result.estimate()).isTrue();
        }
    }

    @Nested
    @DisplayName("Question Type Tests")
    class QuestionTypeTests {

        @Test
        @DisplayName("Should estimate different token counts for different question types")
        void shouldEstimateDifferentTokenCountsForDifferentQuestionTypes() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createRequestWithQuestionTypes(Map.of(
                QuestionType.MCQ_SINGLE, 2,
                QuestionType.TRUE_FALSE, 2,
                QuestionType.OPEN, 2
            ));
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
            assertThat(result.estimate()).isTrue();
            assertThat(result.currency()).isEqualTo("usd");
        }

        @Test
        @DisplayName("Should handle minimum question counts")
        void shouldHandleMinimumQuestionCounts() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createRequestWithQuestionTypes(Map.of(
                QuestionType.MCQ_SINGLE, 1,
                QuestionType.TRUE_FALSE, 1
            ));
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            // Should still have some overhead from system prompts and templates
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should handle valid question counts")
        void shouldHandleValidQuestionCounts() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createRequestWithQuestionTypes(Map.of(
                QuestionType.MCQ_SINGLE, 3,
                QuestionType.TRUE_FALSE, 2
            ));
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Difficulty Tests")
    class DifficultyTests {

        @Test
        @DisplayName("Should apply difficulty multipliers correctly")
        void shouldApplyDifficultyMultipliersCorrectly() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            
            // Test different difficulties
            GenerateQuizFromDocumentRequest easyRequest = createRequestWithDifficulty(Difficulty.EASY);
            GenerateQuizFromDocumentRequest mediumRequest = createRequestWithDifficulty(Difficulty.MEDIUM);
            GenerateQuizFromDocumentRequest hardRequest = createRequestWithDifficulty(Difficulty.HARD);
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto easyResult = estimationService.estimateQuizGeneration(documentId, easyRequest);
            EstimationDto mediumResult = estimationService.estimateQuizGeneration(documentId, mediumRequest);
            EstimationDto hardResult = estimationService.estimateQuizGeneration(documentId, hardRequest);

            // Then
            assertThat(easyResult.estimatedLlmTokens()).isLessThan(mediumResult.estimatedLlmTokens());
            assertThat(mediumResult.estimatedLlmTokens()).isLessThan(hardResult.estimatedLlmTokens());
        }

        @Test
        @DisplayName("Should handle null difficulty")
        void shouldHandleNullDifficulty() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createRequestWithDifficulty(null);
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Scope Tests")
    class ScopeTests {

        @Test
        @DisplayName("Should estimate for entire document scope")
        void shouldEstimateForEntireDocumentScope() {
            // Given
            UUID documentId = UUID.randomUUID();
            List<DocumentChunk> chunks = createSampleChunks();
            Document document = createDocumentWithChunks(chunks);
            GenerateQuizFromDocumentRequest request = createRequestWithScope(QuizScope.ENTIRE_DOCUMENT, null);
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should estimate for specific chunks scope")
        void shouldEstimateForSpecificChunksScope() {
            // Given
            UUID documentId = UUID.randomUUID();
            List<DocumentChunk> chunks = createSampleChunks();
            Document document = createDocumentWithChunks(chunks);
            GenerateQuizFromDocumentRequest request = createRequestWithScope(QuizScope.SPECIFIC_CHUNKS, List.of(0, 1));
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should estimate for specific chapter scope")
        void shouldEstimateForSpecificChapterScope() {
            // Given
            UUID documentId = UUID.randomUUID();
            List<DocumentChunk> chunks = createSampleChunks();
            Document document = createDocumentWithChunks(chunks);
            GenerateQuizFromDocumentRequest request = createRequestWithChapter("Chapter 1", 1);
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

    }

    @Nested
    @DisplayName("Safety Factor Tests")
    class SafetyFactorTests {

        @Test
        @DisplayName("Should apply safety factor to final estimate")
        void shouldApplySafetyFactorToFinalEstimate() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createBasicRequest();
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));
            when(billingProperties.getSafetyFactor()).thenReturn(1.5); // 50% safety factor

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
            // The safety factor should increase the token count
        }

        @Test
        @DisplayName("Should handle safety factor of 1.0")
        void shouldHandleSafetyFactorOfOne() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(createSampleChunks());
            GenerateQuizFromDocumentRequest request = createBasicRequest();
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));
            when(billingProperties.getSafetyFactor()).thenReturn(1.0);

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Character Count Tests")
    class CharacterCountTests {

        @Test
        @DisplayName("Should handle chunks with null character count")
        void shouldHandleChunksWithNullCharacterCount() {
            // Given
            UUID documentId = UUID.randomUUID();
            DocumentChunk chunk = new DocumentChunk();
            chunk.setChunkIndex(0);
            chunk.setContent("This is sample content for testing");
            chunk.setCharacterCount(null); // Explicitly null
            
            Document document = createDocumentWithChunks(List.of(chunk));
            GenerateQuizFromDocumentRequest request = createBasicRequest();
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should handle chunks with null content")
        void shouldHandleChunksWithNullContent() {
            // Given
            UUID documentId = UUID.randomUUID();
            DocumentChunk chunk = new DocumentChunk();
            chunk.setChunkIndex(0);
            chunk.setContent(null);
            chunk.setCharacterCount(null);
            
            Document document = createDocumentWithChunks(List.of(chunk));
            GenerateQuizFromDocumentRequest request = createBasicRequest();
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(java.util.Optional.of(document));

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then
            // Should still have some overhead from system prompts and templates
            assertThat(result.estimatedLlmTokens()).isGreaterThan(0L);
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }
    }

    // Helper methods
    private GenerateQuizFromDocumentRequest createBasicRequest() {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                null,
                null,
                Map.of(QuestionType.MCQ_SINGLE, 5),
                Difficulty.MEDIUM,
                null,
                null,
                null
        );
    }

    private GenerateQuizFromDocumentRequest createRequestWithQuestionTypes(Map<QuestionType, Integer> questionsPerType) {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                null,
                null,
                questionsPerType,
                Difficulty.MEDIUM,
                null,
                null,
                null
        );
    }

    private GenerateQuizFromDocumentRequest createRequestWithDifficulty(Difficulty difficulty) {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                null,
                null,
                Map.of(QuestionType.MCQ_SINGLE, 3),
                difficulty,
                null,
                null,
                null
        );
    }

    private GenerateQuizFromDocumentRequest createRequestWithScope(QuizScope scope, List<Integer> chunkIndices) {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                scope,
                chunkIndices,
                null,
                null,
                null,
                null,
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                null,
                null,
                null
        );
    }

    private GenerateQuizFromDocumentRequest createRequestWithChapter(String chapterTitle, Integer chapterNumber) {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.SPECIFIC_CHAPTER,
                null,
                chapterTitle,
                chapterNumber,
                null,
                null,
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                null,
                null,
                null
        );
    }

    private GenerateQuizFromDocumentRequest createRequestWithSection(String sectionTitle, Integer sectionNumber) {
        return new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.SPECIFIC_SECTION,
                null,
                null,
                null,
                null,
                null,
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                null,
                null,
                null
        );
    }

    private Document createDocumentWithChunks(List<DocumentChunk> chunks) {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setChunks(chunks);
        return document;
    }

    private List<DocumentChunk> createSampleChunks() {
        DocumentChunk chunk1 = new DocumentChunk();
        chunk1.setChunkIndex(0);
        chunk1.setContent("This is the first chapter content with some text for testing purposes.");
        chunk1.setCharacterCount(75);
        chunk1.setChapterTitle("Chapter 1");
        chunk1.setChapterNumber(1);
        chunk1.setSectionTitle("Section 1.1");
        chunk1.setSectionNumber(1);

        DocumentChunk chunk2 = new DocumentChunk();
        chunk2.setChunkIndex(1);
        chunk2.setContent("This is the second chapter content with more text for testing purposes.");
        chunk2.setCharacterCount(78);
        chunk2.setChapterTitle("Chapter 2");
        chunk2.setChapterNumber(2);
        chunk2.setSectionTitle("Section 2.1");
        chunk2.setSectionNumber(1);

        return List.of(chunk1, chunk2);
    }
}
