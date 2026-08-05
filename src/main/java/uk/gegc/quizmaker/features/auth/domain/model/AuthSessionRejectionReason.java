package uk.gegc.quizmaker.features.auth.domain.model;

/**
 * Fixed-cardinality reasons for authentication-session rejection metrics.
 */
public enum AuthSessionRejectionReason {
    INVALID_TOKEN("invalid_token"),
    INACTIVE_SESSION("inactive_session"),
    REPLAYED_TOKEN("replayed_token");

    private final String metricValue;

    AuthSessionRejectionReason(String metricValue) {
        this.metricValue = metricValue;
    }

    public String metricValue() {
        return metricValue;
    }
}
