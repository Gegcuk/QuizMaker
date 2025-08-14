package uk.gegc.quizmaker.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.validation.DifferentFrom;
import uk.gegc.quizmaker.validation.ValidPassword;

@Schema(name = "ChangePasswordRequest", description = "Payload for changing user password")
@DifferentFrom(field = "newPassword", notEqualTo = "currentPassword")
public record ChangePasswordRequest(
        @Schema(description = "Current password", example = "OldP@ssw0rd!")
        @NotBlank(message = "{currentPassword.blank}")
        String currentPassword,

        @Schema(description = "New password", example = "NewP@ssw0rd!")
        @NotBlank(message = "{newPassword.blank}")
        @Size(min = 8, max = 100, message = "{password.length}")
        @ValidPassword
        String newPassword
) {
}
