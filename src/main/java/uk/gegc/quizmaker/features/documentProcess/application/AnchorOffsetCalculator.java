package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.text.Normalizer;
import java.util.*;

import java.util.List;

/**
 * Service for calculating character offsets from text anchors.
 * This provides more reliable offset calculation than AI-generated offsets.
 */
@Component
@Slf4j
public class AnchorOffsetCalculator {

    /**
     * Calculates offsets for a list of document nodes using their text anchors.
     * 
     * @param nodes the nodes with anchors to calculate offsets for
     * @param documentText the full document text to search in
     * @return the same list with calculated offsets
     * @throws AnchorNotFoundException if any anchor cannot be found
     */
    public List<DocumentNode> calculateOffsets(List<DocumentNode> nodes, String documentText) {
        log.debug("Calculating offsets for {} nodes using anchors", nodes.size());
        
        for (DocumentNode node : nodes) {
            calculateNodeOffsets(node, documentText);
        }
        
        log.debug("Successfully calculated offsets for {} nodes", nodes.size());
        return nodes;
    }

    /**
     * Calculates start and end offsets for a single node.
     */
    private void calculateNodeOffsets(DocumentNode node, String documentText) {
        String startAnchor = node.getStartAnchor();
        String endAnchor = node.getEndAnchor();
        
        if (startAnchor == null || startAnchor.trim().isEmpty()) {
            throw new AnchorNotFoundException("Start anchor is null or empty for node: " + node.getTitle());
        }
        
        if (endAnchor == null || endAnchor.trim().isEmpty()) {
            throw new AnchorNotFoundException("End anchor is null or empty for node: " + node.getTitle());
        }
        
        // Find start offset
        int startOffset = findAnchorPosition(documentText, startAnchor, node.getTitle(), "start");
        if (startOffset == -1) {
            throw new AnchorNotFoundException("Start anchor not found: '" + startAnchor + "' for node: " + node.getTitle());
        }
        
        // Find end offset (search from start position to avoid wrong matches)
        int endOffset = findAnchorPosition(documentText, endAnchor, node.getTitle(), "end", startOffset);
        if (endOffset == -1) {
            throw new AnchorNotFoundException("End anchor not found: '" + endAnchor + "' for node: " + node.getTitle());
        }
        
        // For end anchors, we want the position after the anchor text
        endOffset += endAnchor.length();
        
        // Validate that start < end
        if (startOffset >= endOffset) {
            throw new IllegalArgumentException(
                "Invalid anchor positions for node '" + node.getTitle() + "': start=" + startOffset + ", end=" + endOffset
            );
        }
        
        // Validate that offsets are within document bounds
        if (startOffset < 0 || endOffset > documentText.length()) {
            throw new IllegalArgumentException(
                "Anchor positions out of bounds for node '" + node.getTitle() + "': start=" + startOffset + 
                ", end=" + endOffset + ", documentLength=" + documentText.length()
            );
        }
        
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        
        log.debug("Calculated offsets for node '{}': [{}, {})", node.getTitle(), startOffset, endOffset);
    }

    /**
     * Finds the position of an anchor text in the document.
     * Uses case-sensitive search first, then falls back to case-insensitive.
     * 
     * @param documentText the full document text
     * @param anchor the anchor text to find
     * @param nodeTitle the node title for logging
     * @param anchorType "start" or "end" for logging
     * @param fromIndex minimum position to search from (for end anchors)
     * @return the position of the anchor, or -1 if not found
     */
    private int findAnchorPosition(String documentText, String anchor, String nodeTitle, String anchorType, int fromIndex) {
        // Validate anchor length
        if (anchor.length() < 20) {
            log.warn("{} anchor '{}' for node '{}' is too short ({} chars), should be at least 20 characters", 
                    anchorType, anchor, nodeTitle, anchor.length());
        }
        
        // Normalize both document and anchor for Unicode consistency
        String normalizedDoc = Normalizer.normalize(documentText, Normalizer.Form.NFC);
        String normalizedAnchor = Normalizer.normalize(anchor, Normalizer.Form.NFC);
        
        // First try exact match from the specified position
        int position = normalizedDoc.indexOf(normalizedAnchor, fromIndex);
        if (position != -1) {
            return position;
        }
        
        // Try case-insensitive match from the specified position
        String lowerDoc = normalizedDoc.toLowerCase(java.util.Locale.ROOT);
        String lowerAnchor = normalizedAnchor.toLowerCase(java.util.Locale.ROOT);
        position = lowerDoc.indexOf(lowerAnchor, fromIndex);
        if (position != -1) {
            log.warn("Found {} anchor '{}' for node '{}' using case-insensitive search at position {}", 
                    anchorType, anchor, nodeTitle, position);
            return position;
        }
        
        // Note: No fallback to before start position for end anchors to avoid wrong spans
        
        // Try to find partial matches for debugging
        log.warn("{} anchor '{}' not found for node '{}'. Document preview: '{}'", 
                anchorType, anchor, nodeTitle, 
                documentText.substring(0, Math.min(200, documentText.length())));
        
        return -1;
    }

    /**
     * Overload for backward compatibility - searches from the beginning.
     */
    private int findAnchorPosition(String documentText, String anchor, String nodeTitle, String anchorType) {
        return findAnchorPosition(documentText, anchor, nodeTitle, anchorType, 0);
    }

    /**
     * Validates that sibling nodes don't overlap after offset calculation.
     * Parent-child overlaps are expected in a hierarchy.
     */
    public void validateSiblingNonOverlap(List<DocumentNode> nodes) {
        // Group nodes by parent for sibling validation
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

            // Sort siblings by start offset
            siblings.sort((a, b) -> Integer.compare(a.getStartOffset(), b.getStartOffset()));

            // Check for overlapping siblings
            for (int i = 0; i < siblings.size() - 1; i++) {
                DocumentNode current = siblings.get(i);
                DocumentNode next = siblings.get(i + 1);

                if (current.getEndOffset() > next.getStartOffset()) {
                    throw new IllegalArgumentException(
                        "Overlapping siblings in " + parentName + ": " +
                        current.getTitle() + " [" + current.getStartOffset() + "," + current.getEndOffset() + ") and " +
                        next.getTitle() + " [" + next.getStartOffset() + "," + next.getEndOffset() + ")"
                    );
                }
            }

            log.debug("Validated {} siblings under {} for non-overlap", siblings.size(), parentName);
        }
    }

    /**
     * Exception thrown when an anchor cannot be found in the document.
     */
    public static class AnchorNotFoundException extends RuntimeException {
        public AnchorNotFoundException(String message) {
            super(message);
        }
        
        public AnchorNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
