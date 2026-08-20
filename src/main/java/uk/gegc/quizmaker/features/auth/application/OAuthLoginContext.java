package uk.gegc.quizmaker.features.auth.application;

import java.io.Serializable;

public record OAuthLoginContext(
        Mode mode,
        String clientId,
        String redirectUri,
        String codeChallenge
) implements Serializable {

    public static OAuthLoginContext codeExchange(
            String clientId,
            String redirectUri,
            String codeChallenge
    ) {
        return new OAuthLoginContext(Mode.CODE_EXCHANGE, clientId, redirectUri, codeChallenge);
    }

    public static OAuthLoginContext legacy(String redirectUri) {
        return new OAuthLoginContext(Mode.LEGACY, null, redirectUri, null);
    }

    public enum Mode {
        CODE_EXCHANGE,
        LEGACY
    }
}
