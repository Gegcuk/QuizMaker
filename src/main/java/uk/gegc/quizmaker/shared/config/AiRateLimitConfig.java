package uk.gegc.quizmaker.shared.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for AI rate limiting and retry behavior
 */
@Component
@ConfigurationProperties(prefix = "ai.rate-limit")
@Validated
@Data
public class AiRateLimitConfig {
    
    /**
     * Maximum number of retry attempts for AI calls
     */
    private int maxRetries = 5;

    /**
     * Maximum provider dispatches shared by one chunk/type fallback operation.
     */
    @Min(value = 1, message = "ai.rate-limit.max-attempts-per-task must be at least 1")
    private int maxAttemptsPerTask = 5;
    
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
