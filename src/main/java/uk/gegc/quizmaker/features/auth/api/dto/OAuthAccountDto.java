package uk.gegc.quizmaker.features.auth.api.dto;

import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;

import java.time.LocalDateTime;

/**
 * DTO representing an OAuth account linked to a user
 */
public record OAuthAccountDto(
    Long id,
    OAuthProvider provider,
    String email,
    String name,
    String profileImageUrl,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

