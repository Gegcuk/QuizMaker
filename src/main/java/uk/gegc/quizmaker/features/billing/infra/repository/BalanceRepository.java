package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;

import java.util.Optional;
import java.util.UUID;

public interface BalanceRepository extends JpaRepository<Balance, UUID> {
    Optional<Balance> findByUserId(UUID userId);
}

