package uk.gegc.quizmaker.features.user.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Schema(name = "AuthenticatedUserDto", description = "Details of a user")
public record AuthenticatedUserDto(
        @Schema(description = "Unique user identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Username", example = "newUser")
        String username,

        @Schema(description = "Email address", example = "user@example.com")
        String email,

        @Schema(description = "Whether the user is active", example = "true")
        boolean isActive,

        @Schema(description = "Assigned roles", example = "[\"ROLE_USER\"]")
        Set<RoleName> roles,

        @Schema(description = "Account creation timestamp", example = "2025-05-21T15:30:00")
        LocalDateTime createdAt,

        @Schema(description = "Last login timestamp", example = "2025-05-21T16:00:00")
        LocalDateTime lastLoginDate,

        @Schema(description = "Last update timestamp", example = "2025-05-21T16:10:00")
        LocalDateTime updatedAt
) {
}
