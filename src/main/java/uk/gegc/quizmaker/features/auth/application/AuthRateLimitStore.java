package uk.gegc.quizmaker.features.auth.application;

import java.time.Duration;
import java.util.List;

/** Persistence boundary: all supplied dimensions are admitted together or none are consumed. */
public interface AuthRateLimitStore {
    long admit(List<Quota> quotas, boolean consume);

    record Quota(String digest, String dimension, int requests, Duration window, boolean rolling) {
        public Quota {
            if (digest == null || !digest.matches("[0-9a-f]{64}") || dimension == null
                    || !dimension.matches("[a-z-]{1,48}") || requests < 1 || requests > 10000
                    || window == null || window.isZero() || window.isNegative()
                    || window.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Invalid authentication quota configuration");
            }
        }
    }
}
