package uk.gegc.quizmaker.features.auth.domain.model;

/**
 * Durable reasons a server-side authentication session can no longer be used.
 */
public enum AuthSessionRevocationReason {
    LOGOUT,
    REFRESH_TOKEN_REPLAY
}
