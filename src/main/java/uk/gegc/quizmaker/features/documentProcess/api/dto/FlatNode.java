package uk.gegc.quizmaker.features.documentProcess.api.dto;

import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Flat representation of a document node without nested children
 */
public record FlatNode(
        UUID id,
        UUID documentId,
        UUID parentId,
        Integer idx,
        DocumentNode.NodeType type,
        String title,
        Integer startOffset,
        Integer endOffset,
        Short depth,
        BigDecimal aiConfidence,
        String metaJson
) {
}
