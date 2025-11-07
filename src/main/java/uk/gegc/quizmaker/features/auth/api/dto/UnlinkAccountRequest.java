package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;

/**
 * Request to unlink an OAuth account from a user
 */
@Schema(description = "Request to unlink an OAuth social login account from the authenticated user")
public record UnlinkAccountRequest(
    @NotNull(message = "Provider is required")
    @Schema(description = "OAuth provider to unlink from the user account", 
            example = "GOOGLE",
            required = true,
            allowableValues = {"GOOGLE", "GITHUB", "FACEBOOK", "MICROSOFT", "APPLE"})
    OAuthProvider provider
) {}

