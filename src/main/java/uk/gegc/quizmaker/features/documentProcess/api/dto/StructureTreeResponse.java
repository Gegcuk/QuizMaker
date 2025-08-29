package uk.gegc.quizmaker.features.documentProcess.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response containing the tree structure of a document
 */
public record StructureTreeResponse(
        UUID documentId,
        List<NodeView> rootNodes,
        long totalNodes
) {
}
