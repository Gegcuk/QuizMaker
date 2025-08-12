package uk.gegc.quizmaker.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank(message = "Token is required")
        @Size(max = 512, message = "Token must not exceed 512 characters")
        String token
) {}
