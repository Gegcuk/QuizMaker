package uk.gegc.quizmaker.features.billing.infra.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationOperation;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationState;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionMutationOperationRepository
        extends JpaRepository<SubscriptionMutationOperation, UUID> {

    Optional<SubscriptionMutationOperation> findByUserIdAndIdempotencyKeyHash(
            UUID userId,
            String idempotencyKeyHash
    );

    Optional<SubscriptionMutationOperation> findFirstByUserIdAndSubscriptionIdAndStateOrderByCreatedAtAsc(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationState state
    );

    Optional<SubscriptionMutationOperation> findFirstByUserIdAndSubscriptionIdAndRequestHashAndStateOrderByCreatedAtDesc(
            UUID userId,
            String subscriptionId,
            String requestHash,
            SubscriptionMutationState state
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select operation from SubscriptionMutationOperation operation
            where operation.id = :operationId and operation.userId = :userId
            """)
    Optional<SubscriptionMutationOperation> findByIdAndUserIdForUpdate(
            @Param("operationId") UUID operationId,
            @Param("userId") UUID userId
    );

    Optional<SubscriptionMutationOperation> findByIdAndUserId(UUID operationId, UUID userId);
}
