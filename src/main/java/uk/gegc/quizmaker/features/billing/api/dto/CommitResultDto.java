package uk.gegc.quizmaker.features.billing.api.dto;

import java.util.UUID;

public record CommitResultDto(
        UUID reservationId,
        long committedTokens,
        long releasedTokens
) {}

