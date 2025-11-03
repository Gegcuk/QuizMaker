package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import uk.gegc.quizmaker.shared.validation.ValidPassword;

@Schema(name = "ResetPasswordRequest", description = "Request to reset password with new password")
public record ResetPasswordRequest(
        @Schema(description = "New password", example = "NewP@ssw0rd!")
        @NotBlank(message = "Password is required")
        @ValidPassword
        String newPassword
) {}
