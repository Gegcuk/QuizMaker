package uk.gegc.quizmaker.dto.auth;

import java.time.LocalDateTime;

public record VerifyEmailResponse(
        boolean verified,
        String message,
        LocalDateTime verifiedAt
) {}
