package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing the subscription status for a user.
 * Tracks whether a user's subscription is active or blocked.
 */
@Entity
@Table(name = "subscription_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "subscription_id")
    private String subscriptionId;

    @Column(name = "blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "block_reason")
    private String blockReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
