package uk.gegc.quizmaker.features.documentProcess.infra.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.api.dto.FlatNode;
import uk.gegc.quizmaker.features.documentProcess.api.dto.NodeView;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mapper for converting between DocumentNode entities and DTOs.
 */
@Component
public class DocumentNodeMapper {

    /**
     * Converts a DocumentNode entity to FlatNode DTO.
     */
    public FlatNode toFlatNode(DocumentNode node) {
        return new FlatNode(
                node.getId(),
                node.getDocument().getId(),
                node.getParent() != null ? node.getParent().getId() : null,
                node.getIdx(),
                node.getType(),
                node.getTitle(),
                node.getStartOffset(),
                node.getEndOffset(),
                node.getDepth(),
                node.getAiConfidence(),
                node.getMetaJson()
        );
    }

    /**
     * Converts a DocumentNode entity to NodeView DTO (flat structure).
     */
    public NodeView toNodeView(DocumentNode node) {
        return new NodeView(
                node.getId(),
                node.getDocument().getId(),
                node.getParent() != null ? node.getParent().getId() : null,
                node.getIdx(),
                node.getType(),
                node.getTitle(),
                node.getStartOffset(),
                node.getEndOffset(),
                node.getDepth(),
                node.getAiConfidence(),
                node.getMetaJson(),
                List.of() // Empty children - tree building handled separately
        );
    }

    /**
     * Builds a tree structure from a flat list of nodes (no N+1).
     */
    public List<NodeView> buildTree(List<DocumentNode> nodes) {
        Map<UUID, List<DocumentNode>> byParent = nodes.stream()
                .collect(Collectors.groupingBy(n -> n.getParent() == null ? null : n.getParent().getId()));
        return toNodeViews(byParent, null); // null = roots
    }

    private List<NodeView> toNodeViews(Map<UUID, List<DocumentNode>> byParent, UUID parentId) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .map(n -> new NodeView(
                        n.getId(), n.getDocument().getId(),
                        n.getParent() != null ? n.getParent().getId() : null,
                        n.getIdx(), n.getType(), n.getTitle(),
                        n.getStartOffset(), n.getEndOffset(),
                        n.getDepth(), n.getAiConfidence(), n.getMetaJson(),
                        toNodeViews(byParent, n.getId())
                ))
                .toList();
    }

    /**
     * Converts a list of DocumentNode entities to FlatNode DTOs.
     */
    public List<FlatNode> toFlatNodeList(List<DocumentNode> nodes) {
        return nodes.stream()
                .map(this::toFlatNode)
                .collect(Collectors.toList());
    }

    /**
     * Converts a list of DocumentNode entities to NodeView DTOs.
     */
    public List<NodeView> toNodeViewList(List<DocumentNode> nodes) {
        return nodes.stream()
                .map(this::toNodeView)
                .collect(Collectors.toList());
    }
}
