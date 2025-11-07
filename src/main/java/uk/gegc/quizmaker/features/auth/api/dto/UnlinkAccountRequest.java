package uk.gegc.quizmaker.features.auth.api.dto;

import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;

/**
 * Request to unlink an OAuth account from a user
 */
public record UnlinkAccountRequest(
    @NotNull(message = "Provider is required")
    OAuthProvider provider
) {}

