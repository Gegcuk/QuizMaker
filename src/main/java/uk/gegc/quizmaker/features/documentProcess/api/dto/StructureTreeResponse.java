package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Response containing the tree structure of a document
 */
@Schema(name = "StructureTreeResponse", description = "Hierarchical tree structure of document")
public record StructureTreeResponse(
        @Schema(description = "Document UUID")
        UUID documentId,
        
        @Schema(description = "Root nodes of the document structure")
        List<NodeView> rootNodes,
        
        @Schema(description = "Total number of nodes in the structure", example = "25")
        long totalNodes
) {
}
