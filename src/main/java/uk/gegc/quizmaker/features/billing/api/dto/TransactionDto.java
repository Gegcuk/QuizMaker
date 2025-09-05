package uk.gegc.quizmaker.features.billing.api.dto;

import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;

import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionDto(
        UUID id,
        UUID userId,
        TokenTransactionType type,
        TokenTransactionSource source,
        long amountTokens,
        String refId,
        String idempotencyKey,
        Long balanceAfterAvailable,
        Long balanceAfterReserved,
        String metaJson,
        LocalDateTime createdAt
) {}

