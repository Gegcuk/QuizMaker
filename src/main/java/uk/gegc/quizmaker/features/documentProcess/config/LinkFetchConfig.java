package uk.gegc.quizmaker.features.documentProcess.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for link fetching behavior with security controls.
 * Provides SSRF protection and reasonable limits for HTTP operations.
 */
@Configuration
@ConfigurationProperties(prefix = "link.fetch")
@Data
public class LinkFetchConfig {
    
    /**
     * Connection timeout in milliseconds.
     * Default: 3000 ms (3 seconds)
     */
    private int connectTimeoutMs = 3000;
    
    /**
     * Read timeout in milliseconds.
     * Default: 10000 ms (10 seconds)
     */
    private int readTimeoutMs = 10000;
    
    /**
     * Maximum content size in bytes.
     * Default: 5242880 bytes (5 MB)
     */
    private long maxContentSizeBytes = 5_242_880;
    
    /**
     * Maximum number of redirects to follow.
     * Default: 5
     */
    private int maxRedirects = 5;
    
    /**
     * User agent string for HTTP requests.
     * Default: QuizMaker-Bot/1.0
     */
    private String userAgent = "QuizMaker-Bot/1.0";

    /**
     * Maximum number of retries for transient failures (IOExceptions, HTTP 5xx).
     * Default: 2 (total attempts = maxRetries + 1)
     */
    private int maxRetries = 2;

    /**
     * Base backoff in milliseconds between retries. Jittered exponential backoff is applied.
     * Default: 200 ms
     */
    private long retryBackoffMs = 200L;
}

