package uk.gegc.quizmaker.features.auth.application;

import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;

/**
 * Emits authentication-session metrics without user, token, or session identifiers.
 */
public interface AuthSessionMetricsService {

    void recordSessionIssued();

    void recordRefreshSucceeded();

    void recordLogoutSucceeded();

    void recordAccessRejected(AuthSessionRejectionReason reason);

    void recordRefreshRejected(AuthSessionRejectionReason reason);

    void recordSessionStoreFailure();

    void recordExpiredSessionsPurged(int count);
}
