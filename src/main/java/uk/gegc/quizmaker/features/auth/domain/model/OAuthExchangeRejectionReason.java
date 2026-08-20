package uk.gegc.quizmaker.features.auth.domain.model;

public enum OAuthExchangeRejectionReason {
    UNKNOWN("unknown"),
    EXPIRED("expired"),
    REPLAYED("replayed"),
    PKCE_MISMATCH("pkce_mismatch"),
    CLIENT_MISMATCH("client_mismatch"),
    REDIRECT_MISMATCH("redirect_mismatch"),
    USER_UNAVAILABLE("user_unavailable");

    private final String metricValue;

    OAuthExchangeRejectionReason(String metricValue) {
        this.metricValue = metricValue;
    }

    public String metricValue() {
        return metricValue;
    }
}
