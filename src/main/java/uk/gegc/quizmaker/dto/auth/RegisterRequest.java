package uk.gegc.quizmaker.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Username must not be blank")
        @Size(min = 4, max = 20, message = "Username must be between 4 and 20 characters")
        String username,

        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Password must not be blank")
        @Size(min = 8, max = 100, message = "Password length must be at least 8 characters")
        String password
) {
}
