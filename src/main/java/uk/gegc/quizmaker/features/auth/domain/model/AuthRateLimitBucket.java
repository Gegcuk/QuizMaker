package uk.gegc.quizmaker.features.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Schema mapping for test/schema validation; atomic updates are owned by the JDBC adapter. */
@Entity
@Table(name = "auth_rate_limits")
public class AuthRateLimitBucket {
    @Id
    @Column(name = "key_digest", length = 64, nullable = false, columnDefinition = "CHAR(64)")
    private String keyDigest;
    @Column(length = 48, nullable = false)
    private String dimension;
    @Column(nullable = false, columnDefinition = "JSON")
    private String admissions;
    @Column(name = "window_started_at")
    private LocalDateTime windowStartedAt;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
