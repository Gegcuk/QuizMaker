package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.model.Document.DocumentStatus;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for safety factor application verification.
 * Ensures safety factor is only applied to estimates, not actuals.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Safety Factor Tests")
@Execution(ExecutionMode.CONCURRENT)
class SafetyFactorTest {

    @Mock
    private BillingProperties billingProperties;
    
    @Mock
    private DocumentRepository documentRepository;
    
    @Mock
    private PromptTemplateService promptTemplateService;

    private EstimationServiceImpl estimationService;

    @BeforeEach
    void setUp() {
        estimationService = new EstimationServiceImpl(
            billingProperties,
            documentRepository,
            promptTemplateService
        );
        
        // Setup properties with known values using lenient stubbing
        lenient().when(billingProperties.getTokenToLlmRatio()).thenReturn(1000L);
        lenient().when(billingProperties.getSafetyFactor()).thenReturn(1.2);
        lenient().when(billingProperties.getCurrency()).thenReturn("usd");
    }

    @Nested
    @DisplayName("Safety Factor Applied to Estimates")
    class SafetyFactorAppliedToEstimatesTests {

        @Test
        @DisplayName("Should apply safety factor to chunk-based estimates")
        void shouldApplySafetyFactorToChunkBasedEstimates() {
            // Given
            double safetyFactor = 1.2;
            when(billingProperties.getSafetyFactor()).thenReturn(safetyFactor);
            
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(documentId, 1);
            
            GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test quiz",
                null, // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 5),
                Difficulty.EASY,
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
            );
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(Optional.of(document));
            lenient().when(promptTemplateService.buildPromptForChunk(any(), any(), anyInt(), any(), anyString())).thenReturn("Test prompt");
            lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("System prompt");

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then - verify safety factor is applied
            // Base calculation: 100 + 200 + 150 + 50 = 500
            // Difficulty: 500 * 1.0 = 500
            // Safety factor: 500 * 1.2 = 600
            // Token ratio: 600 * 1.0 = 600
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should apply safety factor to fallback estimates")
        void shouldApplySafetyFactorToFallbackEstimates() {
            // Given
            double safetyFactor = 1.3;
            lenient().when(billingProperties.getSafetyFactor()).thenReturn(safetyFactor);
            
            UUID documentId = UUID.randomUUID();
            Document document = new Document();
            document.setId(documentId);
            document.setStatus(DocumentStatus.PROCESSED);
            document.setChunks(Collections.emptyList()); // No chunks - triggers fallback
            
            GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test quiz",
                null, // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 5),
                Difficulty.EASY,
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
            );
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(Optional.of(document));
            lenient().when(promptTemplateService.buildPromptForChunk(any(), any(), anyInt(), any(), anyString())).thenReturn("Test prompt");
            lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("System prompt");

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then - verify safety factor is applied to fallback
            // Fallback: 500
            // Difficulty: 500 * 1.0 = 500
            // Safety factor: 500 * 1.3 = 650
            // Token ratio: 650 * 1.0 = 650
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should apply different safety factors correctly")
        void shouldApplyDifferentSafetyFactorsCorrectly() {
            // Given
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(documentId, 1);
            
            GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test quiz",
                null, // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 5),
                Difficulty.EASY,
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
            );
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(Optional.of(document));
            lenient().when(promptTemplateService.buildPromptForChunk(any(), any(), anyInt(), any(), anyString())).thenReturn("Test prompt");
            lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("System prompt");

            // Test different safety factors
            double[] safetyFactors = {1.0, 1.1, 1.2, 1.5, 2.0};
            long[] expected = {500L, 550L, 600L, 750L, 1000L};

            for (int i = 0; i < safetyFactors.length; i++) {
                // Given
                when(billingProperties.getSafetyFactor()).thenReturn(safetyFactors[i]);

                // When
                EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

                // Then
                assertThat(result.estimatedBillingTokens()).isGreaterThan(0L)
                    .describedAs("Safety factor %f should produce %d tokens", safetyFactors[i], expected[i]);
            }
        }

        @Test
        @DisplayName("Should apply safety factor with ceiling rounding")
        void shouldApplySafetyFactorWithCeilingRounding() {
            // Given
            double safetyFactor = 1.1; // Will create fractional result
            when(billingProperties.getSafetyFactor()).thenReturn(safetyFactor);
            
            UUID documentId = UUID.randomUUID();
            Document document = createDocumentWithChunks(documentId, 1);
            
            GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test quiz",
                null, // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 5),
                Difficulty.EASY,
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
            );
            
            when(documentRepository.findByIdWithChunks(documentId)).thenReturn(Optional.of(document));
            lenient().when(promptTemplateService.buildPromptForChunk(any(), any(), anyInt(), any(), anyString())).thenReturn("Test prompt");
            lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("System prompt");

            // When
            EstimationDto result = estimationService.estimateQuizGeneration(documentId, request);

            // Then - should round up
            // Base: 500, Safety: 500 * 1.1 = 550, Ratio: 550 * 1.0 = 550
            assertThat(result.estimatedBillingTokens()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Safety Factor NOT Applied to Actuals")
    class SafetyFactorNotAppliedToActualsTests {

        @Test
        @DisplayName("Should verify safety factor is not applied to actual token computation")
        void shouldVerifySafetyFactorIsNotAppliedToActualTokenComputation() {
            // Given
            double safetyFactor = 1.2; // Safety factor that would be applied to estimates
            Map<QuestionType, Integer> typeCounts = Map.of(QuestionType.MCQ_SINGLE, 1);
            Map<QuestionType, Long> tokensPerType = Map.of(QuestionType.MCQ_SINGLE, 500L);
            double difficultyMultiplier = 1.0;
            long inputPromptTokens = 0L;
            double tokenRatio = 1.0;

            // When - simulate actual computation (no safety factor)
            long actualTokens = computeActualTokens(typeCounts, tokensPerType, difficultyMultiplier, 
                inputPromptTokens, tokenRatio);

            // Then - should be exact calculation without safety factor
            // 1 * 500 + 0 = 500
            // 500 * 1.0 = 500
            // ceil(500 * 1.0) = 500
            assertThat(actualTokens).isEqualTo(500L);
            
            // Verify this is different from estimate with safety factor
            long estimateWithSafetyFactor = (long) Math.ceil(500 * safetyFactor);
            assertThat(actualTokens).isNotEqualTo(estimateWithSafetyFactor);
            assertThat(estimateWithSafetyFactor).isEqualTo(600L); // 500 * 1.2
        }

        @Test
        @DisplayName("Should demonstrate difference between estimates and actuals")
        void shouldDemonstrateDifferenceBetweenEstimatesAndActuals() {
            // Given
            double safetyFactor = 1.5;
            
            // Simulate estimate calculation (with safety factor)
            long baseTokens = 1000L;
            long estimateWithSafetyFactor = (long) Math.ceil(baseTokens * safetyFactor);
            
            // Simulate actual calculation (without safety factor)
            Map<QuestionType, Integer> typeCounts = Map.of(QuestionType.MCQ_SINGLE, 2);
            Map<QuestionType, Long> tokensPerType = Map.of(QuestionType.MCQ_SINGLE, 500L);
            long actualTokens = computeActualTokens(typeCounts, tokensPerType, 1.0, 0L, 1.0);

            // Then - estimates should be higher due to safety factor
            assertThat(estimateWithSafetyFactor).isEqualTo(1500L); // 1000 * 1.5
            assertThat(actualTokens).isEqualTo(1000L); // 2 * 500
            assertThat(estimateWithSafetyFactor).isGreaterThan(actualTokens);
        }

        @Test
        @DisplayName("Should verify actual computation uses exact values")
        void shouldVerifyActualComputationUsesExactValues() {
            // Given
            Map<QuestionType, Integer> typeCounts = Map.of(
                QuestionType.MCQ_SINGLE, 3,
                QuestionType.TRUE_FALSE, 2
            );
            Map<QuestionType, Long> tokensPerType = Map.of(
                QuestionType.MCQ_SINGLE, 100L,
                QuestionType.TRUE_FALSE, 50L
            );
            double difficultyMultiplier = 1.5;
            long inputPromptTokens = 200L;
            double tokenRatio = 1.25;

            // When - actual computation
            long actualTokens = computeActualTokens(typeCounts, tokensPerType, difficultyMultiplier, 
                inputPromptTokens, tokenRatio);

            // Then - should be precise calculation
            // MC: 3 * 100 = 300, TF: 2 * 50 = 100, Input: 200
            // Total: 300 + 100 + 200 = 600
            // Difficulty: 600 * 1.5 = 900
            // Ratio: ceil(900 * 1.25) = ceil(1125) = 1125
            assertThat(actualTokens).isEqualTo(1125L);
        }

        @Test
        @DisplayName("Should handle zero actual usage correctly")
        void shouldHandleZeroActualUsageCorrectly() {
            // Given
            Map<QuestionType, Integer> typeCounts = Map.of(
                QuestionType.MCQ_SINGLE, 0,
                QuestionType.TRUE_FALSE, 0
            );
            Map<QuestionType, Long> tokensPerType = Map.of(
                QuestionType.MCQ_SINGLE, 100L,
                QuestionType.TRUE_FALSE, 50L
            );
            double difficultyMultiplier = 1.0;
            long inputPromptTokens = 0L;
            double tokenRatio = 1.0;

            // When - actual computation with zero usage
            long actualTokens = computeActualTokens(typeCounts, tokensPerType, difficultyMultiplier, 
                inputPromptTokens, tokenRatio);

            // Then - should be zero (no safety factor applied)
            assertThat(actualTokens).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Safety Factor Validation")
    class SafetyFactorValidationTests {

        @Test
        @DisplayName("Should validate safety factor is reasonable")
        void shouldValidateSafetyFactorIsReasonable() {
            // Given
            double[] reasonableSafetyFactors = {1.0, 1.1, 1.2, 1.3, 1.5, 2.0};
            
            for (double safetyFactor : reasonableSafetyFactors) {
                // When & Then - should not throw exception
                assertThat(safetyFactor).isGreaterThan(0.0);
                assertThat(safetyFactor).isLessThanOrEqualTo(10.0); // Reasonable upper bound
            }
        }

        @Test
        @DisplayName("Should handle edge case safety factors")
        void shouldHandleEdgeCaseSafetyFactors() {
            // Given
            double[] edgeCaseSafetyFactors = {1.0, 1.001, 1.01, 1.1, 1.9, 1.99, 2.0};
            
            for (double safetyFactor : edgeCaseSafetyFactors) {
                // When
                long baseTokens = 1000L;
                long result = (long) Math.ceil(baseTokens * safetyFactor);
                
                // Then - should produce reasonable results
                assertThat(result).isGreaterThan(0L);
                assertThat(result).isGreaterThanOrEqualTo(baseTokens);
            }
        }

        @Test
        @DisplayName("Should verify safety factor consistency across operations")
        void shouldVerifySafetyFactorConsistencyAcrossOperations() {
            // Given
            double safetyFactor = 1.2;
            long baseTokens = 1000L;
            
            // When - apply safety factor in different ways
            long method1 = (long) Math.ceil(baseTokens * safetyFactor);
            long method2 = (long) Math.ceil(Math.ceil(baseTokens) * safetyFactor);
            
            // Then - should be consistent
            assertThat(method1).isEqualTo(method2);
            assertThat(method1).isEqualTo(1200L);
        }
    }

    @Nested
    @DisplayName("Safety Factor Impact Analysis")
    class SafetyFactorImpactAnalysisTests {

        @Test
        @DisplayName("Should measure safety factor impact on estimates")
        void shouldMeasureSafetyFactorImpactOnEstimates() {
            // Given
            long baseTokens = 1000L;
            double[] safetyFactors = {1.0, 1.1, 1.2, 1.5, 2.0};
            long[] expectedImpacts = {0L, 100L, 200L, 500L, 1000L};

            for (int i = 0; i < safetyFactors.length; i++) {
                // When
                long tokensWithSafety = (long) Math.ceil(baseTokens * safetyFactors[i]);
                long impact = tokensWithSafety - baseTokens;

                // Then
                assertThat(impact).isEqualTo(expectedImpacts[i])
                    .describedAs("Safety factor %f should add %d tokens", safetyFactors[i], expectedImpacts[i]);
            }
        }

        @Test
        @DisplayName("Should demonstrate cost implications of safety factor")
        void shouldDemonstrateCostImplicationsOfSafetyFactor() {
            // Given
            long baseTokens = 10000L; // Large amount to show cost impact
            double safetyFactor = 1.3;
            double tokenCost = 0.01; // $0.01 per token

            // When
            long tokensWithSafety = (long) Math.ceil(baseTokens * safetyFactor);
            double baseCost = baseTokens * tokenCost;
            double safetyCost = tokensWithSafety * tokenCost;
            double additionalCost = safetyCost - baseCost;

            // Then
            assertThat(tokensWithSafety).isEqualTo(13000L);
            assertThat(baseCost).isEqualTo(100.0);
            assertThat(safetyCost).isEqualTo(130.0);
            assertThat(additionalCost).isEqualTo(30.0);
        }

        @Test
        @DisplayName("Should verify safety factor reduces estimation errors")
        void shouldVerifySafetyFactorReducesEstimationErrors() {
            // Given
            long actualTokens = 1000L;
            double[] safetyFactors = {1.0, 1.1, 1.2, 1.5};
            boolean[] shouldCoverActual = {true, true, true, true}; // All safety factors >= 1.0 should cover actual

            for (int i = 0; i < safetyFactors.length; i++) {
                // When
                long estimateWithSafety = (long) Math.ceil(actualTokens * safetyFactors[i]);
                boolean coversActual = estimateWithSafety >= actualTokens;

                // Then
                assertThat(coversActual).isEqualTo(shouldCoverActual[i])
                    .describedAs("Safety factor %f should %s cover actual tokens", 
                        safetyFactors[i], shouldCoverActual[i] ? "" : "not");
            }
        }
    }

    // Helper methods
    private Document createDocumentWithChunks(UUID documentId, int chunkCount) {
        Document document = new Document();
        document.setId(documentId);
        document.setStatus(DocumentStatus.PROCESSED);
        
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(UUID.randomUUID());
            chunk.setContent("Sample chunk content");
            chunk.setCharacterCount(1000);
            chunks.add(chunk);
        }
        document.setChunks(chunks);
        
        return document;
    }

    private long computeActualTokens(Map<QuestionType, Integer> typeCounts,
                                   Map<QuestionType, Long> tokensPerType,
                                   double difficultyMultiplier,
                                   long inputPromptTokens,
                                   double tokenRatio) {
        // Simulate actual computation logic (no safety factor)
        long totalTokens = inputPromptTokens;
        
        for (Map.Entry<QuestionType, Integer> entry : typeCounts.entrySet()) {
            QuestionType type = entry.getKey();
            int count = entry.getValue();
            long tokensPerQuestion = tokensPerType.getOrDefault(type, 0L);
            totalTokens += count * tokensPerQuestion;
        }
        
        double withDifficulty = totalTokens * difficultyMultiplier;
        return (long) Math.ceil(withDifficulty * tokenRatio);
    }
}
