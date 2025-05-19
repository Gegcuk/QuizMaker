package uk.gegc.quizmaker.dto.auth;

public record JwtResponse(
        String accessToken,
        String refreshToken,
        long accessExpiresInMs,
        long refreshExpiresInMs
) {
}
