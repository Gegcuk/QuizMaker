package uk.gegc.quizmaker.dto.auth;

public record VerifyEmailResponse(
        boolean verified,
        String message
) {}
