package uk.gegc.quizmaker.features.auth.application;

import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;

/**
 * Rotates one refresh token inside a database transaction.
 */
public interface AuthSessionRefreshService {

    RefreshResult rotate(String refreshToken);

    record RefreshResult(JwtResponse response, AuthSessionRejectionReason rejectionReason) {

        public RefreshResult {
            if ((response == null) == (rejectionReason == null)) {
                throw new IllegalArgumentException("A refresh result must be either successful or rejected");
            }
        }

        public static RefreshResult rotated(JwtResponse response) {
            return new RefreshResult(response, null);
        }

        public static RefreshResult rejected(AuthSessionRejectionReason reason) {
            return new RefreshResult(null, reason);
        }

        public boolean isRejected() {
            return rejectionReason != null;
        }
    }
}
