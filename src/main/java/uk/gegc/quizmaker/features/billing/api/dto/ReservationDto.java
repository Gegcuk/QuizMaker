package uk.gegc.quizmaker.features.billing.api.dto;

import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReservationDto(
        UUID id,
        UUID userId,
        ReservationState state,
        long estimatedTokens,
        long committedTokens,
        LocalDateTime expiresAt,
        UUID jobId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

