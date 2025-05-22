package uk.gegc.quizmaker.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "RegisterRequest", description = "Payload for user registration")
public record RegisterRequest(
        @Schema(description = "Unique username", example = "newUser")
        @NotBlank(message = "Username must not be blank")
        @Size(min = 4, max = 20, message = "Username must be between 4 and 20 characters")
        String username,

        @Schema(description = "User email address", example = "user@example.com")
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid address")
        String email,

        @Schema(description = "Password for the new account", example = "P@ssw0rd!")
        @NotBlank(message = "Password must not be blank")
        @Size(min = 8, max = 100, message = "Password length must be at least 8 characters")
        String password
) {}
