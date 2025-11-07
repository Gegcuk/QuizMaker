package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;

import java.time.LocalDateTime;

/**
 * DTO representing an OAuth account linked to a user
 */
@Schema(description = "OAuth social login account linked to a user")
public record OAuthAccountDto(
    @Schema(description = "Unique identifier of the OAuth account", example = "12345")
    Long id,
    
    @Schema(description = "OAuth provider name", 
            example = "GOOGLE",
            allowableValues = {"GOOGLE", "GITHUB", "FACEBOOK", "MICROSOFT", "APPLE"})
    OAuthProvider provider,
    
    @Schema(description = "Email address from the OAuth provider", example = "user@gmail.com")
    String email,
    
    @Schema(description = "Display name from the OAuth provider", example = "John Doe")
    String name,
    
    @Schema(description = "Profile image URL from the OAuth provider", 
            example = "https://lh3.googleusercontent.com/a/...")
    String profileImageUrl,
    
    @Schema(description = "When the OAuth account was linked to the user", 
            example = "2025-11-07T10:30:00")
    LocalDateTime createdAt,
    
    @Schema(description = "Last time the OAuth account information was updated", 
            example = "2025-11-07T13:45:00")
    LocalDateTime updatedAt
) {}

