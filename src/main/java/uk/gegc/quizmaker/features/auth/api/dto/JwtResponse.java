package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "JwtResponse", description = "JSON Web Tokens and expiration information")
public record JwtResponse(
        @Schema(description = "Access token (JWT)", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        String accessToken,

        @Schema(description = "Refresh token (JWT)", example = "dGhpc2lzYXJlZnJlc2h0b2tlbg==")
        String refreshToken,

        @Schema(description = "Access token validity in milliseconds", example = "3600000")
        long accessExpiresInMs,

        @Schema(description = "Refresh token validity in milliseconds", example = "864000000")
        long refreshExpiresInMs
) {
}