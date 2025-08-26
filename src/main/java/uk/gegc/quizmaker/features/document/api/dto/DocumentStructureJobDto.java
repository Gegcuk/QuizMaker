package uk.gegc.quizmaker.features.document.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for document structure extraction job.
 * <p>
 * Represents the current state and progress of a document structure extraction job,
 * used for API responses in the LRO (Long Running Operation) pattern.
 * <p>
 * Implementation of Day 7 — REST API + Jobs from the chunk processing improvement plan.
 */
@Schema(description = "Document structure extraction job")
public record DocumentStructureJobDto(
        @Schema(description = "Job ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Document ID", example = "550e8400-e29b-41d4-a716-446655440001")
        UUID documentId,

        @Schema(description = "Username of the job owner", example = "john.doe")
        String username,

        @Schema(description = "Current job status")
        DocumentStructureJob.StructureExtractionStatus status,

        @Schema(description = "Extraction strategy used")
        DocumentNode.Strategy strategy,

        @Schema(description = "When the job was started")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startedAt,

        @Schema(description = "When the job was completed (if completed)")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime completedAt,

        @Schema(description = "Error message (if failed)", example = "Failed to extract outline: API timeout")
        String errorMessage,

        @Schema(description = "Error code (if failed)", example = "API_TIMEOUT")
        String errorCode,

        @Schema(description = "Number of nodes extracted", example = "42")
        Integer nodesExtracted,

        @Schema(description = "Progress percentage (0-100)", example = "75.5")
        Double progressPercentage,

        @Schema(description = "Current processing phase", example = "Aligning anchors to offsets")
        String currentPhase,

        @Schema(description = "Total extraction time in seconds", example = "45")
        Long extractionTimeSeconds,

        @Schema(description = "Estimated time remaining in seconds", example = "15")
        Long estimatedTimeRemainingSeconds,

        @Schema(description = "Job duration in seconds", example = "30")
        Long durationSeconds,

        @Schema(description = "Source version hash for determinism")
        String sourceVersionHash,

        @Schema(description = "Whether the job is in a terminal state")
        boolean isTerminal,

        @Schema(description = "Canonical text length in characters", example = "125000")
        Integer canonicalTextLength,

        @Schema(description = "Number of pre-segmentation windows", example = "18")
        Integer preSegmentationWindows,

        @Schema(description = "Number of outline nodes extracted", example = "45")
        Integer outlineNodesExtracted,

        @Schema(description = "Alignment success rate (0.0-1.0)", example = "0.95")
        Double alignmentSuccessRate
) {
    /**
     * Check if the job is completed successfully
     */
    public boolean isCompleted() {
        return status == DocumentStructureJob.StructureExtractionStatus.COMPLETED;
    }

    /**
     * Check if the job failed
     */
    public boolean isFailed() {
        return status == DocumentStructureJob.StructureExtractionStatus.FAILED;
    }

    /**
     * Check if the job is cancelled
     */
    public boolean isCancelled() {
        return status == DocumentStructureJob.StructureExtractionStatus.CANCELLED;
    }

    /**
     * Check if the job is currently processing
     */
    public boolean isProcessing() {
        return status == DocumentStructureJob.StructureExtractionStatus.PROCESSING;
    }

    /**
     * Check if the job is pending
     */
    public boolean isPending() {
        return status == DocumentStructureJob.StructureExtractionStatus.PENDING;
    }

    /**
     * Get a human-readable status description
     */
    public String getStatusDescription() {
        return switch (status) {
            case PENDING -> "Waiting to start";
            case PROCESSING -> currentPhase != null ? currentPhase : "Processing";
            case COMPLETED -> "Completed successfully";
            case FAILED -> errorMessage != null ? "Failed: " + errorMessage : "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    /**
     * Get progress as a percentage string
     */
    public String getProgressString() {
        if (progressPercentage == null) {
            return "0%";
        }
        return String.format("%.1f%%", progressPercentage);
    }
}
