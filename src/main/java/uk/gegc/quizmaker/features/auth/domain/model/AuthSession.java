package uk.gegc.quizmaker.features.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "auth_sessions",
        indexes = {
                @Index(name = "idx_auth_sessions_user_expires_at", columnList = "user_id, expires_at"),
                @Index(name = "idx_auth_sessions_expires_at", columnList = "expires_at")
        }
)
public class AuthSession {

    @Id
    @Column(name = "session_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @Column(name = "refresh_token_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String refreshTokenHash;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "refreshed_at")
    private LocalDateTime refreshedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 32)
    private AuthSessionRevocationReason revocationReason;

    public AuthSession(
            UUID id,
            UUID userId,
            String refreshTokenHash,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
        this.id = id;
        this.userId = userId;
        this.refreshTokenHash = refreshTokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isActiveAt(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void rotateRefreshToken(
            String nextRefreshTokenHash,
            LocalDateTime now,
            LocalDateTime nextExpiresAt
    ) {
        this.refreshTokenHash = nextRefreshTokenHash;
        this.refreshedAt = now;
        this.expiresAt = nextExpiresAt;
    }

    public boolean revoke(LocalDateTime now, AuthSessionRevocationReason reason) {
        if (revokedAt != null) {
            return false;
        }
        this.revokedAt = now;
        this.revocationReason = reason;
        return true;
    }
}
