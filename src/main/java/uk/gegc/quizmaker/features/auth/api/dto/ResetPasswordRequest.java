package uk.gegc.quizmaker.features.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import uk.gegc.quizmaker.shared.validation.ValidPassword;

public record ResetPasswordRequest(
        @NotBlank(message = "Password is required")
        @ValidPassword
        String newPassword
) {}
