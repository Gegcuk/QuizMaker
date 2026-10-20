package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationAdmission;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationWork;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QuizGenerationWorkRepository extends JpaRepository<QuizGenerationWork, UUID> {
    interface QueuePositionRow {
        String getJobId();
        Long getQueuePosition();
        Integer getInconsistent();
    }

    // Rank globally before applying the owner/page filter. Recovery keeps its original
    // sequence, but only never-admitted PENDING jobs expose a public position.
    String QUEUE_POSITION_SQL = """
            WITH waiting AS (
                SELECT w.job_id,
                       ROW_NUMBER() OVER (ORDER BY w.admission_sequence, w.created_at, w.job_id) AS queue_position
                FROM quiz_generation_work w
                JOIN quiz_generation_jobs j ON j.id = w.job_id
                WHERE ((w.state = 'READY' AND w.executions = 0 AND j.status = 'PENDING')
                       OR (w.state = 'RUNNING' AND w.executions > 0 AND j.status = 'PROCESSING' AND w.lease_until <= :now))
                  AND w.schema_version = 1 AND w.admission_sequence > 0 AND w.executions < 2
                  AND (w.deadline_at IS NULL OR w.deadline_at > :asOf)
                  AND (j.billing_reservation_id IS NULL OR
                       (j.billing_state = 'RESERVED' AND j.reservation_expires_at > :billingNow))
                  AND NOT EXISTS (SELECT 1 FROM quiz_generation_output_checkpoints c WHERE c.job_id = w.job_id)
            )
            SELECT BIN_TO_UUID(j.id) AS jobId,
                   CASE WHEN j.status = 'PENDING' AND w.state = 'READY' AND w.executions = 0
                        THEN waiting.queue_position ELSE NULL END AS queuePosition,
                   CASE WHEN j.status = 'PENDING' AND w.job_id IS NOT NULL AND waiting.job_id IS NULL
                        THEN 1 ELSE 0 END AS inconsistent
            FROM quiz_generation_jobs j
            LEFT JOIN quiz_generation_work w ON w.job_id = j.id
            LEFT JOIN waiting ON waiting.job_id = j.id
            WHERE j.id IN (:jobIds) AND j.username = :username
            """;

    @Query(value = QUEUE_POSITION_SQL, nativeQuery = true)
    List<QueuePositionRow> findQueuePositions(Collection<UUID> jobIds, String username, LocalDateTime now,
            LocalDateTime billingNow, Instant asOf);

    // Also supports Hibernate-created integration-test schemas. The production migration seeds it.
    @Modifying
    @Query(value = "INSERT INTO quiz_generation_admission (id, next_sequence) VALUES (1, 1) ON DUPLICATE KEY UPDATE id = 1", nativeQuery = true)
    void initializeAdmission();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from QuizGenerationAdmission a where a.id = 1")
    QuizGenerationAdmission lockAdmission();

    @Query("select count(w) from QuizGenerationWork w where w.state = 'RUNNING' and w.leaseUntil > :now")
    long countRunning(LocalDateTime now);

    @Query("select count(w) from QuizGenerationWork w where w.state = 'READY' or (w.state = 'RUNNING' and w.leaseUntil <= :now)")
    long countWaiting(LocalDateTime now);

    @Query("select count(w) from QuizGenerationWork w where w.state <> 'DONE'")
    long countOutstanding();

    @Query("select min(w.createdAt) from QuizGenerationWork w where w.state <> 'DONE'")
    LocalDateTime findOldestOutstanding();

    @Query("""
            select w.jobId from QuizGenerationWork w
            where w.state = 'READY' or (w.state = 'RUNNING' and w.leaseUntil <= :now)
            order by w.admissionSequence, w.createdAt, w.jobId
            """)
    List<UUID> findDue(LocalDateTime now, Pageable pageable);
}
