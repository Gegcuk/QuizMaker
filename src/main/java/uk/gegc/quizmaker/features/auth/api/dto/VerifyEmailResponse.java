package uk.gegc.quizmaker.features.auth.api.dto;

import java.time.LocalDateTime;

public record VerifyEmailResponse(
        boolean verified,
        String message,
        LocalDateTime verifiedAt
) {}
