package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "subscription_mutation_operations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_smo_user_idempotency_key_hash",
                        columnNames = {"user_id", "idempotency_key_hash"}
                ),
                @UniqueConstraint(
                        name = "uq_smo_stripe_idempotency_key",
                        columnNames = "stripe_idempotency_key"
                )
        },
        indexes = {
                @Index(
                        name = "idx_smo_subscription_state_created",
                        columnList = "user_id, subscription_id, state, created_at"
                )
        }
)
public class SubscriptionMutationOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subscription_status_id", nullable = false, updatable = false)
    private UUID subscriptionStatusId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "subscription_id", nullable = false, updatable = false, length = 255)
    private String subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 16)
    private SubscriptionMutationType operationType;

    @Column(name = "target_price_id", updatable = false, length = 255)
    private String targetPriceId;

    @Column(name = "idempotency_key_hash", updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String idempotencyKeyHash;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(name = "stripe_idempotency_key", nullable = false, updatable = false, length = 96)
    private String stripeIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private SubscriptionMutationState state;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public SubscriptionMutationOperation(
            UUID subscriptionStatusId,
            UUID userId,
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId,
            String idempotencyKeyHash,
            String requestHash,
            String stripeIdempotencyKey,
            SubscriptionMutationState state,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        this.subscriptionStatusId = subscriptionStatusId;
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.operationType = operationType;
        this.targetPriceId = targetPriceId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestHash = requestHash;
        this.stripeIdempotencyKey = stripeIdempotencyKey;
        this.state = state;
        this.createdAt = now;
        this.updatedAt = now;
        this.leaseExpiresAt = leaseExpiresAt;
        this.completedAt = state == SubscriptionMutationState.SUCCEEDED ? now : null;
    }

    public boolean hasSameRequest(String candidateRequestHash) {
        return requestHash.equals(candidateRequestHash);
    }

    public boolean hasActiveLease(LocalDateTime now) {
        return state == SubscriptionMutationState.IN_PROGRESS
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }

    public void acquire(LocalDateTime now, LocalDateTime newLeaseExpiry) {
        state = SubscriptionMutationState.IN_PROGRESS;
        leaseExpiresAt = newLeaseExpiry;
        updatedAt = now;
        completedAt = null;
    }

    public void makeRetryable(LocalDateTime now) {
        if (state != SubscriptionMutationState.SUCCEEDED) {
            state = SubscriptionMutationState.RETRYABLE;
            leaseExpiresAt = null;
            updatedAt = now;
        }
    }

    public void succeed(LocalDateTime now) {
        state = SubscriptionMutationState.SUCCEEDED;
        leaseExpiresAt = null;
        updatedAt = now;
        completedAt = now;
    }
}
