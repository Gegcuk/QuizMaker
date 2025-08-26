package uk.gegc.quizmaker.features.document.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing a document structure extraction job.
 * <p>
 * This entity tracks the progress of asynchronous document structure extraction
 * operations, following the Long Running Operation (LRO) pattern with 202/polling.
 * <p>
 * Implementation of Day 7 — REST API + Jobs from the chunk processing improvement plan.
 */
@Entity
@Table(name = "document_structure_jobs")
@Data
@NoArgsConstructor
public class DocumentStructureJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "username", referencedColumnName = "username", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StructureExtractionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false)
    private DocumentNode.Strategy strategy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "nodes_extracted")
    private Integer nodesExtracted = 0;

    @Column(name = "progress_percentage")
    private Double progressPercentage = 0.0;

    @Column(name = "current_phase")
    private String currentPhase;

    @Column(name = "extraction_time_seconds")
    private Long extractionTimeSeconds;

    @Column(name = "source_version_hash")
    private String sourceVersionHash;

    @Column(name = "canonical_text_length")
    private Integer canonicalTextLength;

    @Column(name = "pre_segmentation_windows")
    private Integer preSegmentationWindows;

    @Column(name = "outline_nodes_extracted")
    private Integer outlineNodesExtracted;

    @Column(name = "alignment_success_rate")
    private Double alignmentSuccessRate;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = StructureExtractionStatus.PENDING;
        }
        if (progressPercentage == null) {
            progressPercentage = 0.0;
        }
        if (nodesExtracted == null) {
            nodesExtracted = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (status == StructureExtractionStatus.COMPLETED || status == StructureExtractionStatus.FAILED) {
            if (completedAt == null) {
                completedAt = LocalDateTime.now();
            }
            if (extractionTimeSeconds == null && startedAt != null) {
                extractionTimeSeconds = java.time.Duration.between(startedAt, completedAt).getSeconds();
            }
        }
    }

    /**
     * Update progress for the extraction job
     */
    public void updateProgress(double progressPercentage, String currentPhase) {
        this.progressPercentage = Math.max(0.0, Math.min(100.0, progressPercentage));
        this.currentPhase = currentPhase;
    }

    /**
     * Mark the job as completed successfully
     */
    public void markCompleted(int nodesExtracted, String sourceVersionHash) {
        this.status = StructureExtractionStatus.COMPLETED;
        this.nodesExtracted = nodesExtracted;
        this.sourceVersionHash = sourceVersionHash;
        this.completedAt = LocalDateTime.now();
        this.progressPercentage = 100.0;
        this.currentPhase = "Completed";
    }

    /**
     * Mark the job as failed
     */
    public void markFailed(String errorMessage, String errorCode) {
        this.status = StructureExtractionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        this.completedAt = LocalDateTime.now();
        this.currentPhase = "Failed";
    }

    /**
     * Mark the job as cancelled
     */
    public void markCancelled() {
        this.status = StructureExtractionStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
        this.currentPhase = "Cancelled";
    }

    /**
     * Check if the job is in a terminal state
     */
    public boolean isTerminal() {
        return status == StructureExtractionStatus.COMPLETED ||
                status == StructureExtractionStatus.FAILED ||
                status == StructureExtractionStatus.CANCELLED;
    }

    /**
     * Get the duration of the job in seconds
     */
    public Long getDurationSeconds() {
        if (startedAt == null) {
            return 0L;
        }
        LocalDateTime endTime = completedAt != null ? completedAt : LocalDateTime.now();
        return java.time.Duration.between(startedAt, endTime).getSeconds();
    }

    /**
     * Get estimated time remaining in seconds
     */
    public Long getEstimatedTimeRemainingSeconds() {
        if (isTerminal() || progressPercentage == null || progressPercentage <= 0) {
            return 0L;
        }

        Long elapsedSeconds = getDurationSeconds();
        double progress = progressPercentage / 100.0;

        if (progress <= 0) {
            return 0L;
        }

        long totalEstimatedSeconds = (long) (elapsedSeconds / progress);
        return Math.max(0L, totalEstimatedSeconds - elapsedSeconds);
    }

    /**
     * Set extraction metrics for completed jobs
     */
    public void setExtractionMetrics(int canonicalTextLength, int preSegmentationWindows, 
                                   int outlineNodesExtracted, double alignmentSuccessRate) {
        this.canonicalTextLength = canonicalTextLength;
        this.preSegmentationWindows = preSegmentationWindows;
        this.outlineNodesExtracted = outlineNodesExtracted;
        this.alignmentSuccessRate = alignmentSuccessRate;
    }

    /**
     * Enumeration for document structure extraction job status
     */
    public enum StructureExtractionStatus {
        PENDING("Pending"),
        PROCESSING("Processing"),
        COMPLETED("Completed"),
        FAILED("Failed"),
        CANCELLED("Cancelled");

        private final String displayName;

        StructureExtractionStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }
}
