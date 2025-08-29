package uk.gegc.quizmaker.features.ai.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.ai.api.dto.ChatResponseDto;
import uk.gegc.quizmaker.features.ai.application.AiChatService;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;
    private final AiRateLimitConfig rateLimitConfig;

    @Override
    public ChatResponseDto sendMessage(String message) {
        Instant start = Instant.now();

        // Validate input
        if (message == null || message.trim().isEmpty()) {
            throw new AiServiceException("Message cannot be null or empty");
        }

        log.info("Sending message to AI: '{}'", message);

        int maxRetries = rateLimitConfig.getMaxRetries();
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                ChatResponse response = chatClient.prompt()
                        .user(message)
                        .call()
                        .chatResponse();

                if (response == null) {
                    throw new AiServiceException("No response received from AI service");
                }

                String aiResponse = response.getResult().getOutput().getText();

                long latency = Duration.between(start, Instant.now()).toMillis();
                int tokensUsed = response.getMetadata() != null ?
                        response.getMetadata().getUsage().getTotalTokens() : aiResponse.length() / 4; // Rough estimate
                String modelUsed = response.getMetadata() != null ?
                        response.getMetadata().getModel() : "gpt-3.5-turbo";

                log.info("AI response received - Model: {}, Tokens: {}, Latency: {}ms",
                        modelUsed, tokensUsed, latency);

                return new ChatResponseDto(aiResponse, modelUsed, latency, tokensUsed);

            } catch (Exception e) {
                log.error("Error calling AI service for message: '{}' (attempt {})", message, retryCount + 1, e);

                // Check if this is a rate limit error (429)
                if (isRateLimitError(e)) {
                    long delayMs = calculateBackoffDelay(retryCount);
                    log.warn("Rate limit hit for chat message (attempt {}). Waiting {} ms before retry.",
                            retryCount + 1, delayMs);
                    
                    if (retryCount < maxRetries - 1) {
                        sleepForRateLimit(delayMs);
                        retryCount++;
                        log.info("Retrying chat message after rate limit delay");
                        continue;
                    } else {
                        throw new AiServiceException("Rate limit exceeded after " + maxRetries + " attempts. Please try again later.", e);
                    }
                }

                if (retryCount < maxRetries - 1) {
                    retryCount++;
                    log.info("Retrying chat message due to error");
                    continue;
                } else {
                    throw new AiServiceException("Failed to get AI response after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
            }
        }

        throw new AiServiceException("Failed to get AI response after " + maxRetries + " attempts");
    }

    /**
     * Check if the exception is a rate limit error (429)
     */
    private boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        // Check for common rate limit indicators
        return message.contains("429") || 
               message.contains("rate limit") || 
               message.contains("rate_limit_exceeded") ||
               message.contains("Too Many Requests") ||
               message.contains("TPM") ||
               message.contains("RPM");
    }

    /**
     * Calculate exponential backoff delay with jitter
     * Uses configuration values for base delay, max delay, and jitter factor
     */
    private long calculateBackoffDelay(int retryCount) {
        // Exponential backoff: 2^retryCount * baseDelay
        long exponentialDelay = rateLimitConfig.getBaseDelayMs() * (long) Math.pow(2, retryCount);
        
        // Add jitter to prevent thundering herd
        double jitterRange = rateLimitConfig.getJitterFactor();
        double jitter = (1.0 - jitterRange) + (Math.random() * 2 * jitterRange);
        
        long delayWithJitter = (long) (exponentialDelay * jitter);
        
        // Cap at maximum delay
        return Math.min(delayWithJitter, rateLimitConfig.getMaxDelayMs());
    }

    /**
     * Sleep for the specified delay during rate limiting
     * This method can be overridden in tests to avoid actual sleeping
     */
    protected void sleepForRateLimit(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting for rate limit", ie);
        }
    }
} 