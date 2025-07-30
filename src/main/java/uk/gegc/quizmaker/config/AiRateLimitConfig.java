package uk.gegc.quizmaker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for AI rate limiting and retry behavior
 */
@Component
@ConfigurationProperties(prefix = "ai.rate-limit")
@Data
public class AiRateLimitConfig {
    
    /**
     * Maximum number of retry attempts for AI calls
     */
    private int maxRetries = 5;
    
    /**
     * Base delay in milliseconds for exponential backoff
     */
    private long baseDelayMs = 1000;
    
    /**
     * Maximum delay in milliseconds (cap for exponential backoff)
     */
    private long maxDelayMs = 60000;
    
    /**
     * Jitter factor for backoff calculation (0.0 = no jitter, 0.5 = ±50% variation)
     */
    private double jitterFactor = 0.25;
} 