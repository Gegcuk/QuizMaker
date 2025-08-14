package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.shared.validation.NoLeadingTrailingSpaces;

@Schema(name = "ForgotPasswordRequest", description = "Payload for initiating password reset")
public record ForgotPasswordRequest(
        @Schema(description = "Email address associated with the account", example = "user@example.com")
        @NotBlank(message = "{email.blank}")
        @Size(max = 254, message = "{email.max}")
        @Email(message = "{email.invalid}")
        @NoLeadingTrailingSpaces
        String email
) {
}
