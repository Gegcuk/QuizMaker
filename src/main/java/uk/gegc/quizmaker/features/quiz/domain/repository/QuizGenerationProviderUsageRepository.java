package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageRecordState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationProviderUsage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuizGenerationProviderUsageRepository
        extends JpaRepository<QuizGenerationProviderUsage, UUID> {

    Optional<QuizGenerationProviderUsage> findByJobIdAndProviderAttemptId(
            UUID jobId,
            UUID providerAttemptId
    );

    long countByJobId(UUID jobId);

    boolean existsByJobIdAndRecordStateNot(UUID jobId, ProviderUsageRecordState recordState);

    // A newer fence or an expired/finished matching fence proves that the original
    // worker lost authority. Without a fence, wait for a terminal job and no live work.
    String LOST_EXECUTION = """
            (
                (p.execution_fence IS NOT NULL AND w.schema_version = 1 AND
                    (w.fence > p.execution_fence OR (w.fence = p.execution_fence AND
                        (w.state = 'DONE' OR (w.state = 'RUNNING' AND w.lease_until <= :now)))))
                OR (j.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND
                    (w.job_id IS NULL OR (w.schema_version = 1 AND
                        (w.state IN ('READY', 'DONE') OR (w.state = 'RUNNING' AND w.lease_until <= :now)))))
            )
            """;

    interface StartedAttempt {
        String getAttemptRowId();
        String getJobId();
        int getInterrupted();
    }

    @Query(value = """
            WITH attempt_page AS (
                SELECT id, job_id, execution_fence
                FROM quiz_generation_provider_usage FORCE INDEX (idx_qgpu_state_unknown_id)
                WHERE record_state = 'STARTED' AND unknown_at IS NULL
                    AND (:after IS NULL OR id > UUID_TO_BIN(:after))
                ORDER BY id LIMIT :batchSize
            )
            SELECT BIN_TO_UUID(p.id) AS attemptRowId, BIN_TO_UUID(p.job_id) AS jobId,
                   CASE WHEN
            """ + LOST_EXECUTION + """
                   THEN 1 ELSE 0 END AS interrupted
            FROM attempt_page p
            JOIN quiz_generation_jobs j ON j.id = p.job_id
            LEFT JOIN quiz_generation_work w ON w.job_id = p.job_id
            ORDER BY p.id
            """, nativeQuery = true)
    List<StartedAttempt> findStartedPage(String after, LocalDateTime now, int batchSize);

    // Match the job-first lock order of provider recording and generation work.
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM quiz_generation_jobs
            WHERE id IN (:jobIds) ORDER BY id FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<String> lockJobsForReconciliation(List<UUID> jobIds);

    @Modifying
    @Query(value = """
            UPDATE quiz_generation_provider_usage p
            JOIN quiz_generation_jobs j ON j.id = p.job_id
            LEFT JOIN quiz_generation_work w ON w.job_id = p.job_id
            SET p.unknown_at = :now
            WHERE p.id IN (:attemptIds) AND p.job_id IN (:jobIds)
                AND p.record_state = 'STARTED' AND p.unknown_at IS NULL AND
            """ + LOST_EXECUTION, nativeQuery = true)
    int markInterrupted(List<UUID> attemptIds, List<UUID> jobIds, LocalDateTime now);

    interface UnresolvedUsage {
        long getUnknownCount();
        LocalDateTime getOldestUnresolvedAt();
        LocalDateTime getOldestUnknownAt();
    }

    @Query(value = """
            SELECT
                (SELECT COUNT(*) FROM (
                    SELECT id FROM quiz_generation_provider_usage
                    WHERE record_state = 'STARTED' AND unknown_at IS NOT NULL LIMIT :countLimit
                ) AS unknown_attempts) AS unknownCount,
                (SELECT recorded_at FROM quiz_generation_provider_usage
                    WHERE record_state = 'STARTED' ORDER BY recorded_at LIMIT 1) AS oldestUnresolvedAt,
                (SELECT unknown_at FROM quiz_generation_provider_usage
                    WHERE record_state = 'STARTED' AND unknown_at IS NOT NULL ORDER BY unknown_at LIMIT 1) AS oldestUnknownAt
            """, nativeQuery = true)
    UnresolvedUsage summarizeUnresolved(int countLimit);
}
