package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Response containing the flat structure of a document
 */
@Schema(name = "StructureFlatResponse", description = "Flat (linear) structure of document nodes")
public record StructureFlatResponse(
        @Schema(description = "Document UUID")
        UUID documentId,
        
        @Schema(description = "List of nodes in document order")
        List<FlatNode> nodes,
        
        @Schema(description = "Total number of nodes", example = "25")
        long totalNodes
) {
}
