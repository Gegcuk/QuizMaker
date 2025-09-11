package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing subscription status entities.
 */
public interface SubscriptionStatusRepository extends JpaRepository<SubscriptionStatus, UUID> {

    /**
     * Find subscription status by user ID.
     * 
     * @param userId the user ID
     * @return optional subscription status
     */
    Optional<SubscriptionStatus> findByUserId(UUID userId);

    /**
     * Find subscription status by Stripe subscription ID.
     * 
     * @param subscriptionId the Stripe subscription ID
     * @return optional subscription status
     */
    Optional<SubscriptionStatus> findBySubscriptionId(String subscriptionId);
}
