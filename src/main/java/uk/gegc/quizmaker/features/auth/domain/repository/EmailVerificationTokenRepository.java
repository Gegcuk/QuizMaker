package uk.gegc.quizmaker.features.auth.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.auth.domain.model.EmailVerificationToken;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    @Query("SELECT e FROM EmailVerificationToken e WHERE e.tokenHash = :tokenHash AND e.used = false AND e.expiresAt > :now")
    Optional<EmailVerificationToken> findByTokenHashAndUsedFalseAndExpiresAtAfter(
            @Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM EmailVerificationToken e
        WHERE e.expiresAt < :now
          AND e.id NOT IN (
            SELECT u.emailVerifiedByTokenId
            FROM User u
            WHERE u.emailVerifiedByTokenId IS NOT NULL
          )
        """)
    void deleteExpiredTokens(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("UPDATE EmailVerificationToken e SET e.used = true WHERE e.userId = :userId AND e.used = false")
    void invalidateUserTokens(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("""
        UPDATE EmailVerificationToken e
        SET e.used = true
        WHERE e.id = :id AND e.used = false AND e.expiresAt > :now
        """)
    int markUsedIfValid(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
