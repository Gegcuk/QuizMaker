package uk.gegc.quizmaker.features.documentProcess.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for document chunking behavior.
 * Allows fine-tuning of chunking thresholds via application properties.
 */
@Configuration
@ConfigurationProperties(prefix = "quizmaker.document.chunking")
@Data
public class DocumentChunkingConfig {
    
    /**
     * Maximum number of tokens for a single chunk.
     * Documents exceeding this will be split into multiple chunks.
     * Default: 40,000 tokens (conservative to avoid API limits)
     */
    private int maxSingleChunkTokens = 40_000;
    
    /**
     * Maximum number of characters for a single chunk.
     * Provides a character-based fallback for chunking decisions.
     * Default: 150,000 characters (~40k tokens)
     */
    private int maxSingleChunkChars = 150_000;
    
    /**
     * Number of tokens to overlap between chunks for context continuity.
     * Default: 5,000 tokens
     */
    private int overlapTokens = 5_000;
    
    /**
     * Model token limit (context window size).
     * Default: 128,000 (GPT-4 Turbo)
     */
    private int modelMaxTokens = 128_000;
    
    /**
     * Tokens reserved for system prompt and response.
     * Default: 5,000 tokens
     */
    private int promptOverheadTokens = 5_000;
    
    /**
     * Enable aggressive chunking for extra safety.
     * When true, uses more conservative limits.
     * Default: true
     */
    private boolean aggressiveChunking = true;
    
    /**
     * Enable emergency fallback chunking.
     * When true, forces chunking if normal chunking fails.
     * Default: true
     */
    private boolean enableEmergencyChunking = true;
}
