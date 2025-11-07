package uk.gegc.quizmaker.features.auth.api.dto;

import java.util.List;

/**
 * Response containing list of OAuth accounts linked to a user
 */
public record LinkedAccountsResponse(
    List<OAuthAccountDto> accounts
) {}

