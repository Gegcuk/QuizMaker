package uk.gegc.quizmaker.dto.ai;

import java.time.LocalDateTime;

/**
 * DTO for AI chat responses
 */
public record ChatResponseDto(
        String message,
        String model,
        long latency,
        int tokensUsed,
        LocalDateTime timestamp
) {
    public ChatResponseDto(String message, String model, long latency, int tokensUsed) {
        this(message, model, latency, tokensUsed, LocalDateTime.now());
    }
} 