package uk.gegc.quizmaker.features.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * DTO for AI chat responses
 */
@Schema(name = "ChatResponseDto", description = "AI chat response with metadata")
public record ChatResponseDto(
        @Schema(description = "AI-generated response message", example = "Quantum computing uses quantum mechanics principles...")
        String message,
        
        @Schema(description = "AI model used", example = "gpt-4")
        String model,
        
        @Schema(description = "Response latency in milliseconds", example = "1250")
        long latency,
        
        @Schema(description = "Tokens consumed", example = "350")
        int tokensUsed,
        
        @Schema(description = "Response timestamp")
        LocalDateTime timestamp
) {
    public ChatResponseDto(String message, String model, long latency, int tokensUsed) {
        this(message, model, latency, tokensUsed, LocalDateTime.now());
    }
} 