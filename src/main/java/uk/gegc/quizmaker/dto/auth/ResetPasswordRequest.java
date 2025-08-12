package uk.gegc.quizmaker.dto.auth;

import jakarta.validation.constraints.NotBlank;
import uk.gegc.quizmaker.validation.ValidPassword;

public record ResetPasswordRequest(
        @NotBlank(message = "Password is required")
        @ValidPassword
        String newPassword
) {}
