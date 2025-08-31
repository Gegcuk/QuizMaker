package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TokenCounter to verify token estimation logic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TokenCounter Tests")
class TokenCounterTest {

    @Mock
    private DocumentChunkingConfig chunkingConfig;

    private TokenCounter tokenCounter;

    @BeforeEach
    void setUp() {
        // Configure mock settings
        when(chunkingConfig.getMaxSingleChunkTokens()).thenReturn(40_000);
        
        tokenCounter = new TokenCounter(chunkingConfig);
    }

    @Test
    @DisplayName("Should estimate tokens for typical English text")
    void shouldEstimateTokensForText() {
        // Given a typical English sentence (approximately 20 words, ~100 characters)
        String text = "The quick brown fox jumps over the lazy dog. This is a sample sentence for testing token counting.";
        
        // When estimating tokens
        int tokens = tokenCounter.estimateTokens(text);
        
        // Then: ~100 chars / 3.8 chars per token = ~26 tokens
        assertThat(tokens).isBetween(20, 30);
    }

    @Test
    @DisplayName("Should handle empty and null text")
    void shouldHandleEmptyText() {
        assertThat(tokenCounter.estimateTokens("")).isEqualTo(0);
        assertThat(tokenCounter.estimateTokens(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("Should estimate tokens for large document")
    void shouldEstimateTokensForLargeDocument() {
        // Given a large document (300,000 words ≈ 1,500,000 characters)
        // This simulates the user's mentioned case
        StringBuilder largeText = new StringBuilder();
        String sampleParagraph = "This is a sample paragraph with multiple sentences. " +
                "Each sentence contains several words to simulate real document content. " +
                "The goal is to test token counting for large documents. ";
        
        // Build approximately 1.5 million characters
        while (largeText.length() < 1_500_000) {
            largeText.append(sampleParagraph);
        }
        
        // When estimating tokens
        int tokens = tokenCounter.estimateTokens(largeText.toString());
        
        // Then: ~1,500,000 chars / 3.8 chars per token = ~394,737 tokens
        // This exceeds the 400,000 token limit mentioned by the user
        assertThat(tokens).isGreaterThan(350_000);
        assertThat(tokens).isLessThan(450_000);
    }

    @Test
    @DisplayName("Should correctly identify when text exceeds token limit")
    void shouldIdentifyWhenTextExceedsLimit() {
        // Given text of different sizes
        String smallText = "Small text";
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            largeText.append("word ");
        }
        
        // When checking against limits
        boolean smallExceeds = tokenCounter.exceedsTokenLimit(smallText, 100_000);
        boolean largeExceeds = tokenCounter.exceedsTokenLimit(largeText.toString(), 100_000);
        
        // Then
        assertThat(smallExceeds).isFalse();
        assertThat(largeExceeds).isTrue();
    }

    @Test
    @DisplayName("Should calculate safe chunk size for model tokens")
    void shouldCalculateSafeChunkSize() {
        // Given model limits
        int modelTokens = 128_000;
        int promptOverhead = 5_000;
        
        // When calculating safe chunk size
        int safeChars = tokenCounter.getSafeChunkSize(modelTokens, promptOverhead);
        
        // Then: (128,000 - 5,000) * 0.8 * 3.8 * 0.9 ≈ 336,000 characters
        assertThat(safeChars).isGreaterThan(280_000);
        assertThat(safeChars).isLessThan(400_000);
    }

    @Test
    @DisplayName("Should estimate max characters for given tokens")
    void shouldEstimateMaxCharsForTokens() {
        // Given a token limit
        int maxTokens = 10_000;
        
        // When estimating max characters
        int maxChars = tokenCounter.estimateMaxCharsForTokens(maxTokens);
        
        // Then: 10,000 * 3.8 * 0.9 = 34,200 characters
        assertThat(maxChars).isGreaterThan(30_000);
        assertThat(maxChars).isLessThan(40_000);
    }

    @Test
    @DisplayName("Should demonstrate fix for 400k token document issue")
    void shouldDemonstrateFixForLargeDocumentIssue() {
        // Given: User's case - document with ~400,000 tokens (~300,000 words)
        // Approximately 300,000 words * 5 chars/word = 1,500,000 characters
        int documentChars = 1_500_000;
        String simulatedLargeDoc = "x".repeat(documentChars);
        
        // When: Checking if it exceeds safe processing limit
        int estimatedTokens = tokenCounter.estimateTokens(simulatedLargeDoc);
        
        // Safe limit for single chunk (accounting for prompt overhead)
        int safeTokenLimit = 128_000 - 5_000; // Model limit minus overhead
        boolean needsChunking = tokenCounter.exceedsTokenLimit(simulatedLargeDoc, safeTokenLimit);
        
        // Then: Document should be identified as needing chunking
        assertThat(estimatedTokens).as("Document should have ~400k tokens")
                .isGreaterThan(350_000);
        assertThat(needsChunking).as("Document should be flagged for chunking")
                .isTrue();
        
        // Calculate how many chunks would be needed
        int safeChunkSizeChars = tokenCounter.getSafeChunkSize(128_000, 5_000);
        int expectedChunks = (int) Math.ceil((double) documentChars / (safeChunkSizeChars * 0.8));
        
        assertThat(expectedChunks).as("Should need multiple chunks")
                .isGreaterThan(3);
        
        System.out.println("Test Results:");
        System.out.println("- Document size: " + documentChars + " chars");
        System.out.println("- Estimated tokens: " + estimatedTokens);
        System.out.println("- Safe chunk size: " + safeChunkSizeChars + " chars");
        System.out.println("- Expected chunks needed: " + expectedChunks);
        System.out.println("- Needs chunking: " + needsChunking);
    }
}
