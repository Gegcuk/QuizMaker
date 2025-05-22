package uk.gegc.quizmaker.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "LoginRequest", description = "Payload for user login")
public record LoginRequest(
        @Schema(description = "Username or email", example = "newUser")
        @NotBlank(message = "Username must not be blank")
        String username,

        @Schema(description = "User password", example = "P@ssw0rd!")
        @NotBlank(message = "Password must not be blank")
        String password
) {}
