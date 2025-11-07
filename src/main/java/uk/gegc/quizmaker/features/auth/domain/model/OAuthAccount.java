package uk.gegc.quizmaker.features.auth.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;

/**
 * Entity representing a user's OAuth 2.0 account from an external provider.
 * Links users to their social login accounts (Google, GitHub, Facebook, etc.)
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(
    name = "oauth_accounts",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_oauth_accounts_provider_user_id",
        columnNames = {"provider", "provider_user_id"}
    )
)
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oauth_account_id", updatable = false, nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * OAuth provider name (google, github, facebook, etc.)
     */
    @Column(name = "provider", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private OAuthProvider provider;

    /**
     * User ID from the OAuth provider
     */
    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    /**
     * Email from OAuth provider
     */
    @Column(name = "email", length = 254)
    private String email;

    /**
     * Full name from OAuth provider
     */
    @Column(name = "name")
    private String name;

    /**
     * Profile image URL from OAuth provider
     */
    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    /**
     * OAuth access token (should be encrypted in production)
     */
    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    /**
     * OAuth refresh token (should be encrypted in production)
     */
    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    /**
     * When the OAuth access token expires
     */
    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

