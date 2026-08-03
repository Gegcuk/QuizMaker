package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable economic idempotency marker for a Stripe Checkout Session.
 */
@Entity
@Table(name = "checkout_session_settlements")
@Getter
@Setter
public class CheckoutSessionSettlement {

    @Id
    @Column(name = "stripe_session_id", nullable = false, updatable = false, length = 255)
    private String stripeSessionId;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;
}
