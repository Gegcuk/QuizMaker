package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;

/**
 * Utility class for estimating token counts for AI model processing.
 * Token estimation is based on the rule of thumb that:
 * - 1 token ≈ 4 characters in English text
 * - 1 token ≈ 0.75 words
 * 
 * These are approximations. For exact counts, we would need to use the actual tokenizer
 * for the specific model being used (e.g., tiktoken for OpenAI models).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenCounter {
    
    private final DocumentChunkingConfig chunkingConfig;
    
    // Conservative estimate: Using a more accurate ratio based on OpenAI's typical tokenization
    // GPT models typically use ~3.5-4 characters per token for English text
    // We'll use 3.8 for better accuracy while still being conservative
    private static final double CHARS_PER_TOKEN = 3.8;
    
    // Average words per token (conservative estimate)
    private static final double WORDS_PER_TOKEN = 0.75;
    
    /**
     * Estimates the number of tokens in a given text.
     * Uses a conservative estimate to avoid exceeding model limits.
     * 
     * @param text the text to count tokens for
     * @return estimated number of tokens
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // Use character count for estimation (more reliable than word count)
        int charCount = text.length();
        int estimatedTokens = (int) Math.ceil(charCount / CHARS_PER_TOKEN);
        
        // Always log token estimation for debugging
        log.info("Token estimation: {} characters -> {} tokens (ratio: {} chars/token)", 
                charCount, estimatedTokens, CHARS_PER_TOKEN);
        
        // Extra warning for large documents using configurable threshold
        int largeDocumentThreshold = chunkingConfig.getMaxSingleChunkTokens();
        if (estimatedTokens > largeDocumentThreshold) {
            log.warn("LARGE DOCUMENT DETECTED: {} tokens estimated from {} characters (threshold: {})", 
                    estimatedTokens, charCount, largeDocumentThreshold);
        }
        
        return estimatedTokens;
    }
    
    /**
     * Estimates the number of characters that would fit within a token limit.
     * 
     * @param maxTokens the maximum number of tokens allowed
     * @return estimated maximum number of characters
     */
    public int estimateMaxCharsForTokens(int maxTokens) {
        // Use a slightly more conservative estimate for safety
        int maxChars = (int) (maxTokens * CHARS_PER_TOKEN * 0.9);
        
        log.debug("Estimated max {} characters for {} tokens", maxChars, maxTokens);
        
        return maxChars;
    }
    
    /**
     * Checks if a text exceeds a given token limit.
     * 
     * @param text the text to check
     * @param maxTokens the maximum number of tokens allowed
     * @return true if the text exceeds the token limit
     */
    public boolean exceedsTokenLimit(String text, int maxTokens) {
        int estimatedTokens = estimateTokens(text);
        boolean exceeds = estimatedTokens > maxTokens;
        
        log.info("Token limit check: {} characters = ~{} tokens, limit = {}, exceeds = {}", 
                text != null ? text.length() : 0, estimatedTokens, maxTokens, exceeds);
        
        if (exceeds) {
            log.warn("Text with {} estimated tokens exceeds limit of {} tokens", 
                    estimatedTokens, maxTokens);
        }
        
        return exceeds;
    }
    
    /**
     * Estimates tokens from word count.
     * 
     * @param wordCount the number of words
     * @return estimated number of tokens
     */
    public int estimateTokensFromWords(int wordCount) {
        return (int) Math.ceil(wordCount / WORDS_PER_TOKEN);
    }
    
    /**
     * Gets the safe chunk size in characters for a given token limit.
     * This accounts for the prompt overhead and uses conservative estimates.
     * 
     * @param maxModelTokens the maximum tokens the model can handle
     * @param promptOverheadTokens estimated tokens used by system prompt and formatting
     * @return safe chunk size in characters
     */
    public int getSafeChunkSize(int maxModelTokens, int promptOverheadTokens) {
        // Reserve tokens for prompt and response
        int availableTokensForContent = maxModelTokens - promptOverheadTokens;
        
        // Further reduce by 20% for safety margin
        int safeTokens = (int) (availableTokensForContent * 0.8);
        
        // Convert to characters
        int safeChars = estimateMaxCharsForTokens(safeTokens);
        
        log.info("Calculated safe chunk size: {} chars for {} model tokens (overhead: {} tokens)", 
                safeChars, maxModelTokens, promptOverheadTokens);
        
        return safeChars;
    }
    
    /**
     * Gets the configured safe chunk size using application configuration.
     * 
     * @return safe chunk size in characters based on configured model limits
     */
    public int getConfiguredSafeChunkSize() {
        int maxModelTokens = chunkingConfig.getModelMaxTokens();
        int promptOverheadTokens = chunkingConfig.getPromptOverheadTokens();
        return getSafeChunkSize(maxModelTokens, promptOverheadTokens);
    }
}
