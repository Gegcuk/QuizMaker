package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "VerifyEmailRequest", description = "Request to verify email with token")
public record VerifyEmailRequest(
        @Schema(description = "Email verification token", example = "abc123def456")
        @NotBlank(message = "Token is required")
        @Size(max = 512, message = "Token must not exceed 512 characters")
        String token
) {}
