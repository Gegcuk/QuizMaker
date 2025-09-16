package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.*;

/**
 * Service for building proper parent-child relationships and assigning indices
 * based on document structure hierarchy.
 */
@Component
@Slf4j
public class NodeHierarchyBuilder {

    /**
     * Builds parent-child relationships and assigns proper indices based on depth.
     * 
     * @param nodes the nodes to process (must be sorted by start offset)
     * @return the same list with proper parent-child relationships and indices
     */
    public List<DocumentNode> buildHierarchy(List<DocumentNode> nodes) {
        if (nodes.isEmpty()) {
            return nodes;
        }

        // Create a mutable copy and sort by start offset to ensure proper ordering
        List<DocumentNode> mutableNodes = new ArrayList<>(nodes);
        mutableNodes.sort((a, b) -> Integer.compare(a.getStartOffset(), b.getStartOffset()));

        // Track indices per parent
        Map<UUID, Integer> perParentIdx = new HashMap<>();
        
        // Stack to track current path in the hierarchy
        Deque<DocumentNode> stack = new ArrayDeque<>();

        for (DocumentNode node : mutableNodes) {
            // Pop stack until we find a parent with depth < current node's depth
            while (!stack.isEmpty() && stack.peek().getDepth() >= node.getDepth()) {
                stack.pop();
            }

            // Set parent (null if stack is empty, meaning this is a root node)
            DocumentNode parent = stack.isEmpty() ? null : stack.peek();
            node.setParent(parent);

            // Assign index within parent's children
            UUID parentKey = parent != null ? parent.getId() : null;
            int idx = perParentIdx.merge(parentKey, 0, (oldV, v) -> oldV + 1);
            node.setIdx(idx);

            // Push current node onto stack
            stack.push(node);
        }

        // Reassign parent end offsets based on their children
        reassignParentEndOffsets(mutableNodes);

        // Validate hierarchy integrity
        validateHierarchy(mutableNodes);

        return mutableNodes;
    }

    /**
     * Reassigns parent end offsets to ensure they properly contain all their children.
     * This ensures that parent nodes span from their start to the end of their last child.
     * 
     * @param nodes the nodes to process
     */
    private void reassignParentEndOffsets(List<DocumentNode> nodes) {
        reassignParentEndOffsetsRecursive(nodes);
    }

    /**
     * Public method to reassign parent end offsets independently.
     * Useful for fixing hierarchy issues after initial processing.
     * 
     * @param nodes the nodes to process
     */
    public void reassignParentEndOffsetsRecursive(List<DocumentNode> nodes) {
        if (nodes.isEmpty()) {
            return;
        }

        // Group nodes by parent to find all children for each parent
        Map<UUID, List<DocumentNode>> childrenByParent = new HashMap<>();
        
        for (DocumentNode node : nodes) {
            UUID parentId = node.getParent() != null ? node.getParent().getId() : null;
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(node);
        }

        // Find maximum depth to process from deepest to shallowest
        int maxDepth = nodes.stream().mapToInt(DocumentNode::getDepth).max().orElse(0);
        
        // Process from deepest level up to ensure proper propagation
        for (int depth = maxDepth; depth > 0; depth--) {
            final int currentDepth = depth;
            List<DocumentNode> parentsAtDepth = nodes.stream()
                    .filter(node -> node.getDepth() == currentDepth - 1)
                    .toList();
            
            for (DocumentNode parent : parentsAtDepth) {
                List<DocumentNode> children = childrenByParent.get(parent.getId());
                if (children == null || children.isEmpty()) {
                    continue;
                }

                // Find the maximum end offset among all children
                int maxChildEndOffset = children.stream()
                        .mapToInt(DocumentNode::getEndOffset)
                        .max()
                        .orElse(parent.getEndOffset());

                // Update parent's end offset if it's smaller than the max child end offset
                if (maxChildEndOffset > parent.getEndOffset()) {
                    int oldEndOffset = parent.getEndOffset();
                    parent.setEndOffset(maxChildEndOffset);
                }
            }
        }

    }

    /**
     * Validates that the hierarchy is properly structured.
     */
    private void validateHierarchy(List<DocumentNode> nodes) {
        // Group nodes by parent
        Map<UUID, List<DocumentNode>> childrenByParent = new HashMap<>();
        
        for (DocumentNode node : nodes) {
            UUID parentId = node.getParent() != null ? node.getParent().getId() : null;
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(node);
        }

        // Validate each group of siblings
        for (Map.Entry<UUID, List<DocumentNode>> entry : childrenByParent.entrySet()) {
            List<DocumentNode> siblings = entry.getValue();
            String parentName = entry.getKey() != null ? 
                    siblings.get(0).getParent().getTitle() : "ROOT";

            // Sort siblings by index
            siblings.sort((a, b) -> Integer.compare(a.getIdx(), b.getIdx()));

            // Check for duplicate indices
            Set<Integer> indices = new HashSet<>();
            for (DocumentNode sibling : siblings) {
                if (!indices.add(sibling.getIdx())) {
                    throw new IllegalStateException(
                        "Duplicate index " + sibling.getIdx() + " in siblings of " + parentName
                    );
                }
            }

            // Check for non-overlapping siblings
            for (int i = 0; i < siblings.size() - 1; i++) {
                DocumentNode current = siblings.get(i);
                DocumentNode next = siblings.get(i + 1);

                if (current.getEndOffset() > next.getStartOffset()) {
                    throw new IllegalStateException(
                        "Overlapping siblings in " + parentName + ": " +
                        current.getTitle() + " [" + current.getStartOffset() + "," + current.getEndOffset() + ") and " +
                        next.getTitle() + " [" + next.getStartOffset() + "," + next.getEndOffset() + ")"
                    );
                }
            }
        }
    }

    /**
     * Validates that parent nodes completely contain their children.
     */
    public void validateParentChildContainment(List<DocumentNode> nodes) {
        for (DocumentNode node : nodes) {
            if (node.getParent() != null) {
                DocumentNode parent = node.getParent();
                
                if (node.getStartOffset() < parent.getStartOffset() || 
                    node.getEndOffset() > parent.getEndOffset()) {
                    throw new IllegalStateException(
                        "Child node '" + node.getTitle() + "' [" + 
                        node.getStartOffset() + "," + node.getEndOffset() + 
                        "] is not contained within parent '" + parent.getTitle() + "' [" +
                        parent.getStartOffset() + "," + parent.getEndOffset() + "]"
                    );
                }
            }
        }
    }
}
