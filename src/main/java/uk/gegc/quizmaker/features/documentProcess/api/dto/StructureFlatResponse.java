package uk.gegc.quizmaker.features.documentProcess.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response containing the flat structure of a document
 */
public record StructureFlatResponse(
        UUID documentId,
        List<FlatNode> nodes,
        long totalNodes
) {
}
