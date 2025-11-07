package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response containing list of OAuth accounts linked to a user
 */
@Schema(description = "Response containing all OAuth social login accounts linked to the authenticated user")
public record LinkedAccountsResponse(
    @Schema(description = "List of linked OAuth accounts", 
            example = "[{\"id\": 12345, \"provider\": \"GOOGLE\", \"email\": \"user@gmail.com\", \"name\": \"John Doe\", \"profileImageUrl\": \"https://...\", \"createdAt\": \"2025-11-07T10:30:00\", \"updatedAt\": \"2025-11-07T13:45:00\"}]")
    List<OAuthAccountDto> accounts
) {}

