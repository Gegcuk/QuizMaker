package uk.gegc.quizmaker.features.auth.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.id = :sessionId")
    Optional<AuthSession> findByIdForUpdate(@Param("sessionId") UUID sessionId);

    @Query("""
            select count(session) > 0
            from AuthSession session
            where session.id = :sessionId
              and session.userId = :userId
              and session.revokedAt is null
              and session.expiresAt > :now
            """)
    boolean existsActiveSession(
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId,
            @Param("now") LocalDateTime now
    );

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
