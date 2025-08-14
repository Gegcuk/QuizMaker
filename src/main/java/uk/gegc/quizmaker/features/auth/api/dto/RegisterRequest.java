package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.validation.NoLeadingTrailingSpaces;
import uk.gegc.quizmaker.validation.ValidPassword;

@Schema(name = "RegisterRequest", description = "Payload for user registration")
public record RegisterRequest(
        @Schema(description = "Unique username", example = "newUser")
        @NotBlank(message = "{username.blank}")
        @Size(min = 4, max = 20, message = "{username.length}")
        @NoLeadingTrailingSpaces
        String username,

        @Schema(description = "User email address", example = "user@example.com")
        @NotBlank(message = "{email.blank}")
        @Size(max = 254, message = "{email.max}")
        @Email(message = "{email.invalid}")
        @NoLeadingTrailingSpaces
        String email,

        @Schema(description = "Password for the new account", example = "P@ssw0rd!")
        @NotBlank(message = "{password.blank}")
        @Size(min = 8, max = 100, message = "{password.length}")
        @ValidPassword
        String password
) {
}
