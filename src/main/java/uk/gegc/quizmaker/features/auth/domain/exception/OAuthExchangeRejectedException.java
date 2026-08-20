package uk.gegc.quizmaker.features.auth.domain.exception;

import lombok.Getter;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;

@Getter
public class OAuthExchangeRejectedException extends RuntimeException {

    private final OAuthExchangeRejectionReason reason;

    public OAuthExchangeRejectedException(OAuthExchangeRejectionReason reason) {
        super(reason == OAuthExchangeRejectionReason.REPLAYED
                ? "OAuth exchange code has already been used"
                : "OAuth exchange code is invalid or expired");
        this.reason = reason;
    }
}
