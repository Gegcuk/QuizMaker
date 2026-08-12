package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOutputCheckpoint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface QuizGenerationOutputCheckpointRepository
        extends JpaRepository<QuizGenerationOutputCheckpoint, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM QuizGenerationOutputCheckpoint c WHERE c.jobId = :jobId")
    int deleteByJobId(@Param("jobId") UUID jobId);

    @Query("""
            SELECT c.jobId
            FROM QuizGenerationOutputCheckpoint c
            JOIN c.job j
            WHERE j.status = :status
              AND j.finalizationState = :finalizationState
              AND c.createdAt <= :cutoff
            ORDER BY c.createdAt, c.jobId
            """)
    List<UUID> findCheckpointedJobIds(
            @Param("status") GenerationStatus status,
            @Param("finalizationState") QuizGenerationFinalizationState finalizationState,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
            SELECT c.jobId
            FROM QuizGenerationOutputCheckpoint c
            JOIN c.job j
            WHERE j.status = :status
              AND j.finalizationState = :finalizationState
              AND j.finalizationStartedAt <= :cutoff
            ORDER BY j.finalizationStartedAt, c.jobId
            """)
    List<UUID> findStaleCheckpointedFinalizationJobIds(
            @Param("status") GenerationStatus status,
            @Param("finalizationState") QuizGenerationFinalizationState finalizationState,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
            SELECT j.id
            FROM QuizGenerationJob j
            WHERE j.status = :status
              AND j.finalizationState = :finalizationState
              AND j.finalizationStartedAt <= :cutoff
              AND NOT EXISTS (
                  SELECT c.jobId
                  FROM QuizGenerationOutputCheckpoint c
                  WHERE c.jobId = j.id
              )
            ORDER BY j.finalizationStartedAt, j.id
            """)
    List<UUID> findStaleUncheckpointedFinalizationJobIds(
            @Param("status") GenerationStatus status,
            @Param("finalizationState") QuizGenerationFinalizationState finalizationState,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
            SELECT j.id
            FROM QuizGenerationJob j
            WHERE j.status = :status
              AND j.finalizationState = :finalizationState
              AND j.billingState = :billingState
              AND j.reservationExpiresAt IS NOT NULL
              AND j.reservationExpiresAt <= :now
              AND NOT EXISTS (
                  SELECT c.jobId
                  FROM QuizGenerationOutputCheckpoint c
                  WHERE c.jobId = j.id
              )
            ORDER BY j.reservationExpiresAt, j.id
            """)
    List<UUID> findExpiredUncheckpointedJobIds(
            @Param("status") GenerationStatus status,
            @Param("finalizationState") QuizGenerationFinalizationState finalizationState,
            @Param("billingState") BillingState billingState,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
