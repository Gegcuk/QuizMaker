package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Flat representation of a document node without nested children
 */
@Schema(name = "FlatNode", description = "Flat (non-nested) representation of a document node")
public record FlatNode(
        @Schema(description = "Node UUID")
        UUID id,
        
        @Schema(description = "Document UUID")
        UUID documentId,
        
        @Schema(description = "Parent node UUID (null for root)")
        UUID parentId,
        
        @Schema(description = "Index within siblings", example = "0")
        Integer idx,
        
        @Schema(description = "Node type", example = "CHAPTER")
        DocumentNode.NodeType type,
        
        @Schema(description = "Node title", example = "Chapter 1: Introduction")
        String title,
        
        @Schema(description = "Start offset in document text", example = "0")
        Integer startOffset,
        
        @Schema(description = "End offset in document text", example = "5000")
        Integer endOffset,
        
        @Schema(description = "Depth in hierarchy (0 = root)", example = "0")
        Short depth,
        
        @Schema(description = "AI confidence score", example = "0.95")
        BigDecimal aiConfidence,
        
        @Schema(description = "Additional metadata as JSON string")
        String metaJson
) {
}
