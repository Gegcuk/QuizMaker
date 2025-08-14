package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "LoginRequest", description = "Payload for user login")
public record LoginRequest(
        @Schema(description = "Username or email", example = "newUser")
        @NotBlank(message = "{username.blank}")
        String username,

        @Schema(description = "User password", example = "P@ssw0rd!")
        @NotBlank(message = "{password.blank}")
        String password
) {
}
