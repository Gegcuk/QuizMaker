package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByStripeSessionId(String stripeSessionId);

    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    Page<Payment> findByUserId(UUID userId, Pageable pageable);
}

