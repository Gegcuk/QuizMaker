package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "OAuthCodeExchangeRequest",
        description = "Automatic browser exchange of a short-lived OAuth callback code. Sensitive values are never logged."
)
public record OAuthCodeExchangeRequest(
        @Schema(description = "Opaque single-use callback code", requiredMode = Schema.RequiredMode.REQUIRED,
                minLength = 43, maxLength = 43, pattern = "[A-Za-z0-9_-]{43}",
                example = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC")
        String code,

        @Schema(description = "Original registered application client", example = "quizzence-web",
                requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64,
                pattern = "[A-Za-z0-9._-]{1,64}")
        String clientId,

        @Schema(description = "Exact allowlisted callback URI used to start login",
                example = "https://www.quizzence.com/oauth2/redirect", requiredMode = Schema.RequiredMode.REQUIRED,
                maxLength = 2048)
        String redirectUri,

        @Schema(description = "RFC 7636 PKCE verifier retained by the browser; 43-128 unreserved characters",
                requiredMode = Schema.RequiredMode.REQUIRED, minLength = 43, maxLength = 128,
                pattern = "[A-Za-z0-9\\-._~]{43,128}",
                example = "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV")
        String codeVerifier
) {
    @Override
    public String toString() {
        return "OAuthCodeExchangeRequest[redacted]";
    }
}
