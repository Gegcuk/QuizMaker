package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for cancellation support in SpringAiStructuredClient.
 * Phase 3 fix: Ensures cancelled jobs don't waste tokens on retries.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Spring AI Structured Client - Cancellation Tests")
class SpringAiStructuredClientCancellationTest {
    
    @Mock
    private ChatClient chatClient;
    
    @Mock
    private PromptTemplateService promptTemplateService;
    
    @Mock
    private AiRateLimitConfig rateLimitConfig;
    
    private SpringAiStructuredClient structuredClient;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        QuestionSchemaRegistry schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        
        structuredClient = new SpringAiStructuredClient(
                chatClient,
                schemaRegistry,
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );
        
        // Use lenient() because some tests cancel before using these configs
        org.mockito.Mockito.lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        org.mockito.Mockito.lenient().when(rateLimitConfig.getBaseDelayMs()).thenReturn(100L);
        org.mockito.Mockito.lenient().when(rateLimitConfig.getMaxDelayMs()).thenReturn(1000L);
        org.mockito.Mockito.lenient().when(rateLimitConfig.getJitterFactor()).thenReturn(0.1);
    }
    
    @Test
    @DisplayName("Should abort immediately when cancellation checker returns true before first attempt")
    void shouldAbortImmediatelyWhenCancelledBeforeFirstAttempt() {
        // Given - Job is already cancelled
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Test content for question generation")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(3)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .cancellationChecker(() -> true)  // Already cancelled!
                .build();
        
        // When
        StructuredQuestionResponse response = structuredClient.generateQuestions(request);
        
        // Then - Should return empty response immediately
        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getWarnings()).containsExactly("Generation cancelled by user");
        assertThat(response.getTokensUsed()).isEqualTo(0L);
        
        // And should NOT call ChatClient at all
        verify(chatClient, times(0)).prompt(any(Prompt.class));
        verify(promptTemplateService, times(0)).buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString());
    }
    
    @Test
    @DisplayName("Should abort after first retry when job cancelled between attempts")
    void shouldAbortAfterFirstRetryWhenCancelledBetweenAttempts() {
        // Given - Job gets cancelled after first attempt
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Test content for question generation")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(3)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .cancellationChecker(() -> {
                    int attempt = attemptCount.incrementAndGet();
                    return attempt > 1;  // Cancel after first attempt
                })
                .build();
        
        // Mock first attempt to fail (triggers retry)
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("test prompt");
        when(chatClient.prompt(any(Prompt.class))).thenThrow(new AiServiceException("Temporary failure"));
        
        // When
        StructuredQuestionResponse response = structuredClient.generateQuestions(request);
        
        // Then - Should abort after first attempt
        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getWarnings()).containsExactly("Generation cancelled by user");
        assertThat(response.getTokensUsed()).isEqualTo(0L);
        
        // First attempt was made, but no retry
        verify(promptTemplateService, times(1)).buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString());
    }
    
    @Test
    @DisplayName("Should continue retrying when cancellation checker returns false")
    void shouldContinueRetryingWhenNotCancelled() {
        // Given - Job is NOT cancelled
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Test content")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(3)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .cancellationChecker(() -> false)  // NOT cancelled
                .build();
        
        // Mock all attempts to fail
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("test prompt");
        when(chatClient.prompt(any(Prompt.class))).thenThrow(new AiServiceException("Failure"));
        
        // When/Then - Should exhaust all retries
        try {
            structuredClient.generateQuestions(request);
        } catch (AiServiceException e) {
            // Expected to fail after retries
            assertThat(e.getMessage()).contains("Failed to generate structured questions after 3 attempts");
        }
        
        // Should have tried all 3 attempts
        verify(promptTemplateService, times(3)).buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString());
    }
    
    @Test
    @DisplayName("Should work normally when cancellation checker is null")
    void shouldWorkNormallyWhenCancellationCheckerIsNull() {
        // Given - No cancellation checker provided (backward compatibility)
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Test content")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(3)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .cancellationChecker(null)  // No cancellation checking
                .build();
        
        // Mock all attempts to fail
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("test prompt");
        when(chatClient.prompt(any(Prompt.class))).thenThrow(new AiServiceException("Failure"));
        
        // When/Then - Should exhaust all retries normally
        try {
            structuredClient.generateQuestions(request);
        } catch (AiServiceException e) {
            // Expected to fail after retries
            assertThat(e.getMessage()).contains("Failed to generate structured questions after 3 attempts");
        }
        
        // Should have tried all 3 attempts (same as before cancellation feature)
        verify(promptTemplateService, times(3)).buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString());
    }
    
    @Test
    @DisplayName("Should save tokens by aborting on cancellation instead of retrying")
    void shouldSaveTokensByAbortingOnCancellation() {
        // Given - Job will be cancelled after first failed attempt
        AtomicInteger callCount = new AtomicInteger(0);
        
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Test content")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(3)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .cancellationChecker(() -> {
                    int count = callCount.incrementAndGet();
                    return count > 1;  // Cancel before retry
                })
                .build();
        
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("test prompt");
        when(chatClient.prompt(any(Prompt.class))).thenThrow(new AiServiceException("Failure"));
        
        // When
        StructuredQuestionResponse response = structuredClient.generateQuestions(request);
        
        // Then - Should return cancelled response
        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getTokensUsed()).isEqualTo(0L);
        assertThat(response.getWarnings()).containsExactly("Generation cancelled by user");
        
        // Tokens saved: Would have made 2 more attempts (3 total) without cancellation
        // With cancellation: Only 1 attempt made
        verify(promptTemplateService, times(1)).buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString());
    }
}

