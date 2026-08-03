package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.CheckoutSessionSettlement;

public interface CheckoutSessionSettlementRepository extends JpaRepository<CheckoutSessionSettlement, String> {
}
