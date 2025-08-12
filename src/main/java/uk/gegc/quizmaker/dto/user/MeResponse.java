package uk.gegc.quizmaker.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(name = "MeResponse", description = "Profile details for the current user")
public record MeResponse(
        @Schema(description = "User ID") UUID id,
        @Schema(description = "Username") String username,
        @Schema(description = "Email") String email,
        @Schema(description = "Display name") String displayName,
        @Schema(description = "Bio") String bio,
        @Schema(description = "Avatar URL") String avatarUrl,
        @Schema(description = "User preferences") Map<String, Object> preferences,
        @Schema(description = "Account creation time") LocalDateTime joinedAt,
        @Schema(description = "Email verified flag") boolean verified,
        @Schema(description = "Assigned roles") List<String> roles
) {}


