package uk.gegc.quizmaker.features.documentProcess.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Structured records for Spring AI function calling in document structure generation.
 */
public class DocumentStructureRecords {

    /**
     * Request record for document structure generation.
     */
    public record DocumentStructureRequest(
            @JsonProperty("content") String content,
            @JsonProperty("profile") String profile,
            @JsonProperty("granularity") String granularity,
            @JsonProperty("charCount") int charCount
    ) {}

    /**
     * Response record for document structure generation.
     */
    public record DocumentStructureResponse(
            @JsonProperty("nodes") List<StructureNode> nodes
    ) {}

    /**
     * Individual structure node record.
     */
    public record StructureNode(
            @JsonProperty("type") String type,
            @JsonProperty("title") String title,
            @JsonProperty("start_anchor") String startAnchor,
            @JsonProperty("end_anchor") String endAnchor,
            @JsonProperty("depth") int depth,
            @JsonProperty("confidence") double confidence
    ) {
        /**
         * Convert to DocumentNode entity.
         */
        public DocumentNode toDocumentNode(int index) {
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setIdx(index);
            try {
                node.setType(DocumentNode.NodeType.valueOf(type.toUpperCase()));
            } catch (IllegalArgumentException e) {
                node.setType(DocumentNode.NodeType.OTHER);
            }
            node.setTitle(title);
            node.setStartAnchor(startAnchor);
            node.setEndAnchor(endAnchor);

            // Clamp depth to non-negative values
            int safeDepth = Math.max(0, depth);
            node.setDepth((short) safeDepth);

            // Clamp confidence to [0.0, 1.0] and handle NaN
            double c = Double.isNaN(confidence) ? 0.8 : confidence;
            double safeConf = Math.min(1.0, Math.max(0.0, c));
            node.setAiConfidence(BigDecimal.valueOf(safeConf));
            
            return node;
        }
    }
}
