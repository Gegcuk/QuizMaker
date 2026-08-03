package uk.gegc.quizmaker.features.quiz.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationType;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOperation;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;

public interface QuizGenerationOperationRepository extends JpaRepository<QuizGenerationOperation, UUID> {

    Optional<QuizGenerationOperation> findByUserIdAndOperationTypeAndIdempotencyKey(
            UUID userId,
            GenerationOperationType operationType,
            String idempotencyKey
    );

    Optional<QuizGenerationOperation> findByIdAndUserId(UUID operationId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select operation from QuizGenerationOperation operation
            where operation.id = :operationId and operation.userId = :userId
            """)
    Optional<QuizGenerationOperation> findByIdAndUserIdForUpdate(
            @Param("operationId") UUID operationId,
            @Param("userId") UUID userId
    );

    @Modifying
    @Query("delete from QuizGenerationOperation operation where operation.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
