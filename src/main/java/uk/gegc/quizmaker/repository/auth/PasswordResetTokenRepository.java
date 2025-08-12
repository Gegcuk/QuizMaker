package uk.gegc.quizmaker.repository.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.auth.PasswordResetToken;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    @Query("SELECT p FROM PasswordResetToken p WHERE p.tokenHash = :tokenHash AND p.used = false AND p.expiresAt > :now")
    Optional<PasswordResetToken> findByTokenHashAndUsedFalseAndExpiresAtAfter(
            @Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiresAt < :now")
    void deleteExpiredTokens(@Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE PasswordResetToken p SET p.used = true WHERE p.userId = :userId AND p.used = false")
    void invalidateUserTokens(@Param("userId") UUID userId);
}
