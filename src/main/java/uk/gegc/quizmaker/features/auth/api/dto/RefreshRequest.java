package uk.gegc.quizmaker.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RefreshToken", description = "Payload to refresh an access token")
public record RefreshRequest(
        @Schema(description = "Refresh token to exchange", example = "dGhpc2lzYXJlZnJlc2h0b2tlbg==")
        String refreshToken
) {
}
