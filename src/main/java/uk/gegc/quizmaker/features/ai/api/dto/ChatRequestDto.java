package uk.gegc.quizmaker.features.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for AI chat requests
 */
public record ChatRequestDto(
        @NotBlank(message = "Message cannot be blank")
        @Size(max = 2000, message = "Message must not exceed 2000 characters")
        String message
) {
}