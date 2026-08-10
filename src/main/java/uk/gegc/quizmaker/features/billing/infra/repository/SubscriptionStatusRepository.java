package uk.gegc.quizmaker.features.billing.infra.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;

import java.util.Optional;
import java.util.List;
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

    /**
     * Returns every local association for a Stripe subscription so authorization can reject ambiguous data.
     */
    List<SubscriptionStatus> findAllBySubscriptionId(String subscriptionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select status from SubscriptionStatus status where status.userId = :userId")
    Optional<SubscriptionStatus> findByUserIdForUpdate(@Param("userId") UUID userId);
}
