package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "RefreshToken", description = "Payload to refresh an access token")
public record RefreshRequest(
        @NotBlank
        @Schema(description = "Current single-use refresh token to exchange", example = "dGhpc2lzYXJlZnJlc2h0b2tlbg==")
        String refreshToken
) {
}
