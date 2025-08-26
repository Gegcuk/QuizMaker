package uk.gegc.quizmaker.features.document.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;

import java.time.LocalDateTime;
import java.util.Collection;
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
    Optional<DocumentStructureJob> findByIdAndUser_Username(UUID jobId, String username);

    /**
     * Find all jobs for a user
     */
    Page<DocumentStructureJob> findByUser_UsernameOrderByStartedAtDesc(String username, Pageable pageable);

    /**
     * Find jobs by status
     */
    List<DocumentStructureJob> findByStatusOrderByStartedAtAsc(DocumentStructureJob.StructureExtractionStatus status);

    /**
     * Find active jobs (non-terminal status)
     */
    List<DocumentStructureJob> findByStatusInOrderByStartedAtAsc(Collection<DocumentStructureJob.StructureExtractionStatus> statuses);

    /**
     * Find jobs by document ID
     */
    List<DocumentStructureJob> findByDocumentIdOrderByStartedAtDesc(UUID documentId);

    /**
     * Find jobs by user and document ID
     */
    List<DocumentStructureJob> findByUser_UsernameAndDocumentIdOrderByStartedAtDesc(String username, UUID documentId);

    /**
     * Find jobs created within a time range
     */
    List<DocumentStructureJob> findByStartedAtBetweenOrderByStartedAtDesc(LocalDateTime start, LocalDateTime end);

    /**
     * Find jobs that have been running too long (stuck jobs)
     */
    @Query("select j from DocumentStructureJob j where j.status in (:statuses) and j.startedAt < :cutoff")
    List<DocumentStructureJob> findStuckJobs(@Param("cutoff") LocalDateTime cutoff,
          @Param("statuses") Collection<DocumentStructureJob.StructureExtractionStatus> statuses);

    /**
     * Check if there's an active job for a document
     */
    boolean existsByDocumentIdAndStatusIn(UUID documentId,
          Collection<DocumentStructureJob.StructureExtractionStatus> statuses);

    /**
     * Find the most recent completed job for a document
     */
    Optional<DocumentStructureJob> findFirstByDocumentIdAndStatusOrderByCompletedAtDesc(
          UUID documentId, DocumentStructureJob.StructureExtractionStatus status);

    /**
     * Count total jobs for a user
     */
    long countByUser_Username(String username);

    /**
     * Count jobs by status for a user
     */
    long countByUser_UsernameAndStatus(String username, DocumentStructureJob.StructureExtractionStatus status);

    /**
     * Get average extraction time for a user
     */
    @Query("select avg(j.extractionTimeSeconds) from DocumentStructureJob j where j.user.username = :username and j.extractionTimeSeconds is not null")
    Double getAverageExtractionTimeByUsername(@Param("username") String username);

    /**
     * Get total nodes extracted for a user
     */
    @Query("select sum(j.nodesExtracted) from DocumentStructureJob j where j.user.username = :username and j.nodesExtracted is not null")
    Long getTotalNodesExtractedByUsername(@Param("username") String username);

    /**
     * Get last job created time for a user
     */
    @Query("select max(j.startedAt) from DocumentStructureJob j where j.user.username = :username")
    Optional<LocalDateTime> getLastJobCreatedByUsername(@Param("username") String username);

    /**
     * Delete jobs older than specified date
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from DocumentStructureJob j where j.status in (:terminal) and j.completedAt < :cutoff")
    int deleteOldCompletedJobs(@Param("cutoff") LocalDateTime cutoff,
         @Param("terminal") Collection<DocumentStructureJob.StructureExtractionStatus> terminal);
}
