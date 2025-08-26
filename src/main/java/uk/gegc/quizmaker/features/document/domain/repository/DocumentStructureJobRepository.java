package uk.gegc.quizmaker.features.document.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DocumentStructureJob entities.
 * <p>
 * Provides data access methods for document structure extraction jobs,
 * supporting the async job processing pattern.
 */
@Repository
public interface DocumentStructureJobRepository extends JpaRepository<DocumentStructureJob, UUID> {

    /**
     * Find job by ID and username for security
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.id = :jobId AND j.user.username = :username")
    Optional<DocumentStructureJob> findByIdAndUsername(@Param("jobId") UUID jobId, @Param("username") String username);

    /**
     * Find all jobs for a user
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.user.username = :username ORDER BY j.startedAt DESC")
    Page<DocumentStructureJob> findByUsername(@Param("username") String username, Pageable pageable);

    /**
     * Find jobs by status
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.status = :status ORDER BY j.startedAt ASC")
    List<DocumentStructureJob> findByStatus(@Param("status") DocumentStructureJob.StructureExtractionStatus status);

    /**
     * Find active jobs (non-terminal status)
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.status IN ('PENDING', 'PROCESSING') ORDER BY j.startedAt ASC")
    List<DocumentStructureJob> findActiveJobs();

    /**
     * Find jobs by document ID
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.documentId = :documentId ORDER BY j.startedAt DESC")
    List<DocumentStructureJob> findByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Find jobs by user and document ID
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.user.username = :username AND j.documentId = :documentId ORDER BY j.startedAt DESC")
    List<DocumentStructureJob> findByUsernameAndDocumentId(@Param("username") String username, @Param("documentId") UUID documentId);

    /**
     * Find jobs created within a time range
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.startedAt BETWEEN :start AND :end ORDER BY j.startedAt DESC")
    List<DocumentStructureJob> findByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Find jobs that have been running too long (stuck jobs)
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.status IN ('PENDING', 'PROCESSING') AND j.startedAt < :cutoffTime ORDER BY j.startedAt ASC")
    List<DocumentStructureJob> findStuckJobs(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Count jobs by status for a user
     */
    @Query("SELECT COUNT(j) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.status = :status")
    long countByUsernameAndStatus(@Param("username") String username, @Param("status") DocumentStructureJob.StructureExtractionStatus status);

    /**
     * Find the most recent completed job for a document
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.documentId = :documentId AND j.status = 'COMPLETED' ORDER BY j.completedAt DESC LIMIT 1")
    Optional<DocumentStructureJob> findMostRecentCompletedJobForDocument(@Param("documentId") UUID documentId);

    /**
     * Check if there's an active job for a document
     */
    @Query("SELECT COUNT(j) > 0 FROM DocumentStructureJob j WHERE j.documentId = :documentId AND j.status IN ('PENDING', 'PROCESSING')")
    boolean hasActiveJobForDocument(@Param("documentId") UUID documentId);

    /**
     * Find jobs older than specified days for cleanup
     */
    @Query("SELECT j FROM DocumentStructureJob j WHERE j.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND j.completedAt < :cutoffDate")
    List<DocumentStructureJob> findOldCompletedJobs(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Delete jobs older than specified date
     */
    @Query("DELETE FROM DocumentStructureJob j WHERE j.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND j.completedAt < :cutoffDate")
    int deleteOldCompletedJobs(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Count total jobs for a user
     */
    @Query("SELECT COUNT(j) FROM DocumentStructureJob j WHERE j.user.username = :username")
    long countTotalJobsByUsername(@Param("username") String username);

    /**
     * Count completed jobs for a user
     */
    @Query("SELECT COUNT(j) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.status = 'COMPLETED'")
    long countCompletedJobsByUsername(@Param("username") String username);

    /**
     * Count failed jobs for a user
     */
    @Query("SELECT COUNT(j) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.status = 'FAILED'")
    long countFailedJobsByUsername(@Param("username") String username);

    /**
     * Count cancelled jobs for a user
     */
    @Query("SELECT COUNT(j) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.status = 'CANCELLED'")
    long countCancelledJobsByUsername(@Param("username") String username);

    /**
     * Count active jobs for a user
     */
    @Query("SELECT COUNT(j) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.status IN ('PENDING', 'PROCESSING')")
    long countActiveJobsByUsername(@Param("username") String username);

    /**
     * Get average extraction time for a user
     */
    @Query("SELECT AVG(j.extractionTimeSeconds) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.extractionTimeSeconds IS NOT NULL")
    Double getAverageExtractionTimeByUsername(@Param("username") String username);

    /**
     * Get total nodes extracted for a user
     */
    @Query("SELECT SUM(j.nodesExtracted) FROM DocumentStructureJob j WHERE j.user.username = :username AND j.nodesExtracted IS NOT NULL")
    Long getTotalNodesExtractedByUsername(@Param("username") String username);

    /**
     * Get last job created time for a user
     */
    @Query("SELECT MAX(j.startedAt) FROM DocumentStructureJob j WHERE j.user.username = :username")
    Optional<LocalDateTime> getLastJobCreatedByUsername(@Param("username") String username);
}
