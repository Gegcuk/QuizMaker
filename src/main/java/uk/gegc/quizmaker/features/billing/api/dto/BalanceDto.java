package uk.gegc.quizmaker.features.billing.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record BalanceDto(
        UUID userId,
        long availableTokens,
        long reservedTokens,
        LocalDateTime updatedAt
) {}

