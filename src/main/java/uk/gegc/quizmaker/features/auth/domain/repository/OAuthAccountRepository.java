package uk.gegc.quizmaker.features.auth.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing OAuth account entities
 */
@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    /**
     * Find an OAuth account by provider and provider user ID
     */
    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    /**
     * Find all OAuth accounts for a user
     */
    @Query("SELECT oa FROM OAuthAccount oa WHERE oa.user.id = :userId")
    List<OAuthAccount> findByUserId(@Param("userId") UUID userId);

    /**
     * Find an OAuth account by provider and email
     */
    Optional<OAuthAccount> findByProviderAndEmail(OAuthProvider provider, String email);

    /**
     * Check if a user has a specific OAuth provider linked
     */
    @Query("SELECT COUNT(oa) > 0 FROM OAuthAccount oa WHERE oa.user.id = :userId AND oa.provider = :provider")
    boolean existsByUserIdAndProvider(@Param("userId") UUID userId, @Param("provider") OAuthProvider provider);

    /**
     * Find OAuth account by provider and provider user ID with user eagerly fetched
     */
    @Query("SELECT oa FROM OAuthAccount oa JOIN FETCH oa.user u LEFT JOIN FETCH u.roles WHERE oa.provider = :provider AND oa.providerUserId = :providerUserId")
    Optional<OAuthAccount> findByProviderAndProviderUserIdWithUser(
        @Param("provider") OAuthProvider provider,
        @Param("providerUserId") String providerUserId
    );
}

