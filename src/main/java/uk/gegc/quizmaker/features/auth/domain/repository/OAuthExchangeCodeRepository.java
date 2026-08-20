package uk.gegc.quizmaker.features.auth.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeCode;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OAuthExchangeCodeRepository extends JpaRepository<OAuthExchangeCode, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from OAuthExchangeCode code where code.codeHash = :codeHash")
    Optional<OAuthExchangeCode> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OAuthExchangeCode code where code.expiresAt <= :cutoff")
    int deleteExpiredAtOrBefore(@Param("cutoff") LocalDateTime cutoff);
}
