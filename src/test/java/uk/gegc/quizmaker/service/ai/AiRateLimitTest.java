package uk.gegc.quizmaker.service.ai;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.application.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class AiRateLimitTest {

    @Mock
    private AiRateLimitConfig rateLimitConfig;
    
    @Mock
    private Logger aiResponseLogger;
    
    @Mock
    private InternalBillingService internalBillingService;

    @Mock
    private TransactionTemplate transactionTemplate;
    
    @Mock
    private StructuredAiClient structuredAiClient;

    @Test
    void testIsRateLimitError_With429Error() {
        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        Exception rateLimitException = new RuntimeException("429 - Rate limit exceeded");
        assertTrue(aiService.isRateLimitError(rateLimitException));
    }

    @Test
    void testIsRateLimitError_WithRateLimitMessage() {
        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        Exception rateLimitException = new RuntimeException("rate_limit_exceeded");
        assertTrue(aiService.isRateLimitError(rateLimitException));
    }

    @Test
    void testIsRateLimitError_WithTPMMessage() {
        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        Exception rateLimitException = new RuntimeException("TPM limit reached");
        assertTrue(aiService.isRateLimitError(rateLimitException));
    }

    @Test
    void testIsRateLimitError_WithRegularError() {
        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        Exception regularException = new RuntimeException("Connection timeout");
        assertFalse(aiService.isRateLimitError(regularException));
    }

    @Test
    void testIsRateLimitError_WithNullMessage() {
        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        Exception nullMessageException = new RuntimeException();
        assertFalse(aiService.isRateLimitError(nullMessageException));
    }

    @Test
    void testCalculateBackoffDelay_FirstRetry() {
        // Set up configuration for this test
        when(rateLimitConfig.getBaseDelayMs()).thenReturn(1000L);
        when(rateLimitConfig.getMaxDelayMs()).thenReturn(10000L);
        when(rateLimitConfig.getJitterFactor()).thenReturn(0.25);

        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        long delay = aiService.calculateBackoffDelay(0);
        // Should be around 1000ms with jitter
        assertTrue(delay >= 750 && delay <= 1250);
    }

    @Test
    void testCalculateBackoffDelay_SecondRetry() {
        // Set up configuration for this test
        when(rateLimitConfig.getBaseDelayMs()).thenReturn(1000L);
        when(rateLimitConfig.getMaxDelayMs()).thenReturn(10000L);
        when(rateLimitConfig.getJitterFactor()).thenReturn(0.25);

        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        long delay = aiService.calculateBackoffDelay(1);
        // Should be around 2000ms with jitter
        assertTrue(delay >= 1500 && delay <= 2500);
    }

    @Test
    void testCalculateBackoffDelay_ThirdRetry() {
        // Set up configuration for this test
        when(rateLimitConfig.getBaseDelayMs()).thenReturn(1000L);
        when(rateLimitConfig.getMaxDelayMs()).thenReturn(10000L);
        when(rateLimitConfig.getJitterFactor()).thenReturn(0.25);

        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        long delay = aiService.calculateBackoffDelay(2);
        // Should be around 4000ms with jitter
        assertTrue(delay >= 3000 && delay <= 5000);
    }

    @Test
    void testCalculateBackoffDelay_RespectsMaxDelay() {
        // Set up configuration for this test
        when(rateLimitConfig.getBaseDelayMs()).thenReturn(1000L);
        when(rateLimitConfig.getMaxDelayMs()).thenReturn(10000L);
        when(rateLimitConfig.getJitterFactor()).thenReturn(0.25);

        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );

        long delay = aiService.calculateBackoffDelay(10); // Very high retry count
        // Should be capped at maxDelayMs (10000)
        assertEquals(10000L, delay);
    }

    @Test
    void testCalculateBackoffDelay_WithZeroJitter() {
        // Set up configuration for this test with zero jitter
        when(rateLimitConfig.getBaseDelayMs()).thenReturn(1000L);
        when(rateLimitConfig.getMaxDelayMs()).thenReturn(10000L);
        when(rateLimitConfig.getJitterFactor()).thenReturn(0.0);

        AiQuizGenerationServiceImpl aiService = new AiQuizGenerationServiceImpl(
                null, null, null, null, null, null, null, null, rateLimitConfig, internalBillingService, transactionTemplate, structuredAiClient
        );
        
        long delay = aiService.calculateBackoffDelay(1);
        // Should be exactly 2000ms (no jitter)
        assertEquals(2000L, delay);
    }
} 