package uk.gegc.quizmaker.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.validation.ValidPassword;

@Schema(name = "ResetPasswordRequest", description = "Payload for resetting user password")
public record ResetPasswordRequest(
        @Schema(description = "Password reset token", example = "reset-token-123")
        @NotBlank(message = "{token.blank}")
        @Size(max = 512, message = "{token.max}")
        String token,

        @Schema(description = "New password", example = "NewP@ssw0rd!")
        @NotBlank(message = "{newPassword.blank}")
        @Size(min = 8, max = 100, message = "{password.length}")
        @ValidPassword
        String newPassword
) {
}
