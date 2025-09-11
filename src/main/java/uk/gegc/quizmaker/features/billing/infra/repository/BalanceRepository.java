package uk.gegc.quizmaker.features.billing.infra.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;

import java.util.Optional;
import java.util.UUID;

public interface BalanceRepository extends JpaRepository<Balance, UUID> {
    Optional<Balance> findByUserId(UUID userId);
    
    /**
     * Find balance by user ID with pessimistic write lock.
     * Alternative to optimistic locking for high-contention scenarios.
     * Use with caution as it can cause deadlocks and reduced concurrency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Balance b WHERE b.userId = :userId")
    Optional<Balance> findByUserIdForUpdate(@Param("userId") UUID userId);
}

