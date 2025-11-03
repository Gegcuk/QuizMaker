package uk.gegc.quizmaker.features.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for AI chat requests
 */
@Schema(name = "ChatRequestDto", description = "Request to send a message to AI")
public record ChatRequestDto(
        @Schema(description = "Message to send to AI", example = "Explain quantum computing in simple terms")
        @NotBlank(message = "Message cannot be blank")
        @Size(max = 2000, message = "Message must not exceed 2000 characters")
        String message
) {
}