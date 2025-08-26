package uk.gegc.quizmaker.features.document.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.api.dto.DocumentStructureJobDto;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;

/**
 * Manual mapper for DocumentStructureJob entities and DTOs.
 * <p>
 * Provides mapping between domain entities and API DTOs
 * for document structure extraction jobs.
 * <p>
 * Implementation of Day 7 — REST API + Jobs from the chunk processing improvement plan.
 */
@Component
public class DocumentStructureJobMapper {

    /**
     * Map DocumentStructureJob entity to DTO
     *
     * @param job the entity
     * @return the DTO
     */
    public DocumentStructureJobDto toDto(DocumentStructureJob job) {
        if (job == null) {
            return null;
        }

        return new DocumentStructureJobDto(
                job.getId(),
                job.getDocumentId(),
                job.getUser() != null ? job.getUser().getUsername() : null,
                job.getStatus(),
                job.getStrategy(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage(),
                job.getErrorCode(),
                job.getNodesExtracted(),
                job.getProgressPercentage(),
                job.getCurrentPhase(),
                job.getExtractionTimeSeconds(),
                job.getEstimatedTimeRemainingSeconds(),
                job.getDurationSeconds(),
                job.getSourceVersionHash(),
                job.isTerminal(),
                job.getCanonicalTextLength(),
                job.getPreSegmentationWindows(),
                job.getOutlineNodesExtracted(),
                job.getAlignmentSuccessRate()
        );
    }
}
