package uk.gegc.quizmaker.features.billing.api.dto;

import java.util.UUID;

public record ReleaseResultDto(
        UUID reservationId,
        long releasedTokens
) {}

