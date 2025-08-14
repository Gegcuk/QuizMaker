package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizGenerationJobRepository extends JpaRepository<QuizGenerationJob, UUID> {

    /**
     * Find all jobs for a specific user, ordered by start time descending
     */
    List<QuizGenerationJob> findByUser_UsernameOrderByStartedAtDesc(String username);

    /**
     * Find all jobs for a specific user with pagination
     */
    Page<QuizGenerationJob> findByUser_UsernameOrderByStartedAtDesc(String username, Pageable pageable);

    /**
     * Find jobs by document ID and status
     */
    List<QuizGenerationJob> findByDocumentIdAndStatus(UUID documentId, GenerationStatus status);

    /**
     * Find the most recent job for a document
     */
    Optional<QuizGenerationJob> findFirstByDocumentIdOrderByStartedAtDesc(UUID documentId);

    /**
     * Find active jobs for a user
     */
    @Query("SELECT j FROM QuizGenerationJob j WHERE j.user.username = :username AND j.status IN ('PENDING', 'PROCESSING') ORDER BY j.startedAt DESC")
    List<QuizGenerationJob> findActiveJobsByUsername(@Param("username") String username);

    /**
     * Find jobs that have been running for too long (potential stuck jobs)
     */
    @Query("SELECT j FROM QuizGenerationJob j WHERE j.status = 'PROCESSING' AND j.startedAt < :cutoffTime")
    List<QuizGenerationJob> findStuckJobs(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Find jobs by status
     */
    List<QuizGenerationJob> findByStatus(GenerationStatus status);

    /**
     * Find jobs by status with pagination
     */
    Page<QuizGenerationJob> findByStatus(GenerationStatus status, Pageable pageable);

    /**
     * Find jobs by multiple statuses
     */
    List<QuizGenerationJob> findByStatusIn(List<GenerationStatus> statuses);

    /**
     * Find jobs by status that were started before a specific time
     */
    List<QuizGenerationJob> findByStatusAndStartedAtBefore(GenerationStatus status, LocalDateTime time);

    /**
     * Count jobs by status for a user
     */
    @Query("SELECT COUNT(j) FROM QuizGenerationJob j WHERE j.user.username = :username AND j.status = :status")
    long countByUsernameAndStatus(@Param("username") String username, @Param("status") GenerationStatus status);

    /**
     * Find jobs created within a time range
     */
    @Query("SELECT j FROM QuizGenerationJob j WHERE j.startedAt BETWEEN :startTime AND :endTime ORDER BY j.startedAt DESC")
    List<QuizGenerationJob> findByStartedAtBetween(@Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * Find failed jobs for a user
     */
    @Query("SELECT j FROM QuizGenerationJob j WHERE j.user.username = :username AND j.status IN ('FAILED', 'CANCELLED') ORDER BY j.startedAt DESC")
    List<QuizGenerationJob> findFailedJobsByUsername(@Param("username") String username);

    /**
     * Find completed jobs for a user
     */
    @Query("SELECT j FROM QuizGenerationJob j WHERE j.user.username = :username AND j.status = 'COMPLETED' ORDER BY j.completedAt DESC")
    List<QuizGenerationJob> findCompletedJobsByUsername(@Param("username") String username);

    /**
     * Delete old completed jobs (cleanup)
     */
    @Query("DELETE FROM QuizGenerationJob j WHERE j.status = 'COMPLETED' AND j.completedAt < :cutoffTime")
    void deleteOldCompletedJobs(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Find jobs that need cleanup (old failed/cancelled jobs)
     */
    @Query("SELECT j FROM QuizGenerationJob j WHERE j.status IN ('FAILED', 'CANCELLED') AND j.completedAt < :cutoffTime")
    List<QuizGenerationJob> findJobsForCleanup(@Param("cutoffTime") LocalDateTime cutoffTime);
} 