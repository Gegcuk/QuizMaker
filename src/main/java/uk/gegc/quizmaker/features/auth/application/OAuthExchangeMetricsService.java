package uk.gegc.quizmaker.features.auth.application;

import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;

public interface OAuthExchangeMetricsService {

    void recordCodeIssued();

    void recordExchangeSucceeded();

    void recordExchangeRejected(OAuthExchangeRejectionReason reason);

    void recordLegacyRedirect();

    void recordStoreFailure();

    void recordExpiredCodesPurged(int count);
}
