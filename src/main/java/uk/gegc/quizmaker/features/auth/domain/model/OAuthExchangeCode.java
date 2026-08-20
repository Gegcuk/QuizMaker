package uk.gegc.quizmaker.features.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "oauth_exchange_codes",
        indexes = {
                @Index(name = "idx_oec_user_id", columnList = "user_id"),
                @Index(name = "idx_oec_expires_at", columnList = "expires_at")
        }
)
public class OAuthExchangeCode {

    @Id
    @Column(name = "code_hash", nullable = false, updatable = false, length = 64,
            columnDefinition = "CHAR(64)")
    private String codeHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "client_id", nullable = false, updatable = false, length = 64)
    private String clientId;

    @Column(name = "redirect_uri", nullable = false, updatable = false, length = 2048)
    private String redirectUri;

    @Column(name = "pkce_challenge", nullable = false, updatable = false, length = 43,
            columnDefinition = "CHAR(43)")
    private String pkceChallenge;

    @Column(name = "pkce_method", nullable = false, updatable = false, length = 8)
    private String pkceMethod;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public OAuthExchangeCode(
            String codeHash,
            UUID userId,
            String clientId,
            String redirectUri,
            String pkceChallenge,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt
    ) {
        this.codeHash = requireLength(codeHash, 64, "codeHash");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.clientId = requireBounded(clientId, 64, "clientId");
        this.redirectUri = requireBounded(redirectUri, 2048, "redirectUri");
        this.pkceChallenge = requireLength(pkceChallenge, 43, "pkceChallenge");
        this.pkceMethod = "S256";
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void consume(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        if (consumedAt != null) {
            throw new IllegalStateException("OAuth exchange code is already consumed");
        }
        if (now.isBefore(issuedAt) || isExpiredAt(now)) {
            throw new IllegalStateException("OAuth exchange code cannot be consumed at this time");
        }
        consumedAt = now;
    }

    private static String requireLength(String value, int length, String name) {
        if (value == null || value.length() != length) {
            throw new IllegalArgumentException(name + " must contain exactly " + length + " characters");
        }
        return value;
    }

    private static String requireBounded(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be present and no longer than " + maxLength + " characters");
        }
        return value;
    }
}
