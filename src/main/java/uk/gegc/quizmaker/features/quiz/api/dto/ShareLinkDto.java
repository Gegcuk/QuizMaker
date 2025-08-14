package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "ShareLinkDto", description = "View of a share link without exposing the raw token")
public record ShareLinkDto(
        @Schema(description = "Share link id") UUID id,
        @Schema(description = "Quiz id") UUID quizId,
        @Schema(description = "Creator id") UUID createdBy,
        @Schema(description = "Scope") ShareLinkScope scope,
        @Schema(description = "Expiry") Instant expiresAt,
        @Schema(description = "One-time link?") boolean oneTime,
        @Schema(description = "Revoked at") Instant revokedAt,
        @Schema(description = "Created at") Instant createdAt
) {}


