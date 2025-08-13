package uk.gegc.quizmaker.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.Map;

@Schema(name = "UpdateUserProfileRequest", description = "Request to update user profile.\n\nPATCH semantics: omitted field => no change; present with null => clear the value; present with non-null => set sanitized value.")
public record UpdateUserProfileRequest(
        @Schema(description = "Display name (max 50 characters)")
        @Size(max = 50, message = "Display name must not exceed 50 characters")
        String displayName,
        
        @Schema(description = "Bio (max 500 characters)")
        @Size(max = 500, message = "Bio must not exceed 500 characters")
        String bio,
        
        @Schema(description = "User preferences as JSON object")
        Map<String, Object> preferences
) {}
