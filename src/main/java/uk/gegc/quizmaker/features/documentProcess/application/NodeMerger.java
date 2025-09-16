package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for merging and deduplicating nodes from different document chunks.
 * Handles overlapping nodes, conflicts, and hierarchy consistency.
 */
@Service
@Slf4j
public class NodeMerger {

    private static final double SIMILARITY_THRESHOLD = 0.8; // 80% similarity for merging
    private static final int MIN_OVERLAP_CHARS = 50; // Minimum overlap to consider merging

    /**
     * Merges nodes from multiple chunks into a single coherent structure.
     * 
     * @param chunkResults list of node lists from each chunk
     * @param chunkOffsets list of chunk offset information
     * @return merged and deduplicated list of nodes
     */
    public List<DocumentNode> mergeChunkNodes(List<List<DocumentNode>> chunkResults, 
                                            List<DocumentChunker.DocumentChunk> chunks) {
        log.info("Merging {} chunk results with {} total nodes", 
            chunkResults.size(), chunkResults.stream().mapToInt(List::size).sum());
        
        if (chunkResults.size() == 1) {
            return chunkResults.get(0);
        }
        
        // Step 1: Adjust node offsets to global document coordinates
        List<DocumentNode> allNodes = adjustNodeOffsets(chunkResults, chunks);
        
        // Step 2: Group nodes by similarity
        List<NodeGroup> nodeGroups = groupSimilarNodes(allNodes);
        
        // Step 3: Merge each group into a single node
        List<DocumentNode> mergedNodes = mergeNodeGroups(nodeGroups);
        
        // Step 4: Sort by start offset and assign indices
        mergedNodes.sort(Comparator.comparing(DocumentNode::getStartOffset));
        assignIndices(mergedNodes);
        
        log.info("Merged {} chunk results into {} final nodes", chunkResults.size(), mergedNodes.size());
        return mergedNodes;
    }
    
    /**
     * Adjusts node offsets from chunk-relative to document-relative coordinates.
     */
    private List<DocumentNode> adjustNodeOffsets(List<List<DocumentNode>> chunkResults, 
                                               List<DocumentChunker.DocumentChunk> chunks) {
        List<DocumentNode> allNodes = new ArrayList<>();
        
        for (int i = 0; i < chunkResults.size(); i++) {
            List<DocumentNode> chunkNodes = chunkResults.get(i);
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            int chunkOffset = chunk.getStartOffset();
            
            for (DocumentNode node : chunkNodes) {
                // Create a copy with adjusted offsets
                DocumentNode adjustedNode = new DocumentNode();
                adjustedNode.setType(node.getType());
                adjustedNode.setTitle(node.getTitle());
                adjustedNode.setStartAnchor(node.getStartAnchor());
                adjustedNode.setEndAnchor(node.getEndAnchor());
                adjustedNode.setStartOffset(node.getStartOffset() + chunkOffset);
                adjustedNode.setEndOffset(node.getEndOffset() + chunkOffset);
                adjustedNode.setDepth(node.getDepth());
                adjustedNode.setAiConfidence(node.getAiConfidence());
                adjustedNode.setMetaJson(node.getMetaJson());
                
                allNodes.add(adjustedNode);
            }
        }
        
        return allNodes;
    }
    
    /**
     * Groups similar nodes together for merging.
     */
    private List<NodeGroup> groupSimilarNodes(List<DocumentNode> nodes) {
        List<NodeGroup> groups = new ArrayList<>();
        Set<DocumentNode> processed = new HashSet<>();
        
        for (DocumentNode node : nodes) {
            if (processed.contains(node)) {
                continue;
            }
            
            NodeGroup group = new NodeGroup();
            group.addNode(node);
            processed.add(node);
            
            // Find similar nodes
            for (DocumentNode other : nodes) {
                if (processed.contains(other)) {
                    continue;
                }
                
                if (areNodesSimilar(node, other)) {
                    group.addNode(other);
                    processed.add(other);
                }
            }
            
            groups.add(group);
        }
        
        return groups;
    }
    
    /**
     * Determines if two nodes are similar enough to be merged.
     */
    private boolean areNodesSimilar(DocumentNode node1, DocumentNode node2) {
        // Check if nodes overlap significantly
        int overlapStart = Math.max(node1.getStartOffset(), node2.getStartOffset());
        int overlapEnd = Math.min(node1.getEndOffset(), node2.getEndOffset());
        
        if (overlapEnd <= overlapStart) {
            return false; // No overlap
        }
        
        int overlapSize = overlapEnd - overlapStart;
        int minSize = Math.min(node1.getEndOffset() - node1.getStartOffset(), 
                             node2.getEndOffset() - node2.getStartOffset());
        
        // Must have significant overlap
        if (overlapSize < MIN_OVERLAP_CHARS || overlapSize < minSize * 0.3) {
            return false;
        }
        
        // Check title similarity
        double titleSimilarity = calculateSimilarity(node1.getTitle(), node2.getTitle());
        if (titleSimilarity < SIMILARITY_THRESHOLD) {
            return false;
        }
        
        // Check type compatibility
        if (node1.getType() != node2.getType()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Calculates similarity between two strings using Jaccard similarity.
     */
    private double calculateSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null) {
            return 0.0;
        }
        
        Set<String> set1 = new HashSet<>(Arrays.asList(str1.toLowerCase().split("\\s+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(str2.toLowerCase().split("\\s+")));
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Merges each group of similar nodes into a single node.
     */
    private List<DocumentNode> mergeNodeGroups(List<NodeGroup> groups) {
        List<DocumentNode> mergedNodes = new ArrayList<>();
        
        for (NodeGroup group : groups) {
            if (group.getNodes().size() == 1) {
                mergedNodes.add(group.getNodes().get(0));
            } else {
                mergedNodes.add(mergeNodeGroup(group));
            }
        }
        
        return mergedNodes;
    }
    
    /**
     * Merges a group of similar nodes into a single node.
     */
    private DocumentNode mergeNodeGroup(NodeGroup group) {
        List<DocumentNode> nodes = group.getNodes();
        
        // Find the best representative node (highest confidence, most complete)
        DocumentNode bestNode = nodes.stream()
            .max(Comparator.comparing(node -> 
                (node.getAiConfidence() != null ? node.getAiConfidence().doubleValue() : 0.0) +
                (node.getEndOffset() - node.getStartOffset()) / 1000.0)) // Prefer longer nodes
            .orElse(nodes.get(0));
        
        // Calculate merged boundaries
        int mergedStart = nodes.stream().mapToInt(DocumentNode::getStartOffset).min().orElse(0);
        int mergedEnd = nodes.stream().mapToInt(DocumentNode::getEndOffset).max().orElse(0);
        
        // Create merged node
        DocumentNode mergedNode = new DocumentNode();
        mergedNode.setType(bestNode.getType());
        mergedNode.setTitle(bestNode.getTitle());
        mergedNode.setStartAnchor(bestNode.getStartAnchor());
        mergedNode.setEndAnchor(bestNode.getEndAnchor());
        mergedNode.setStartOffset(mergedStart);
        mergedNode.setEndOffset(mergedEnd);
        mergedNode.setDepth(bestNode.getDepth());
        
        // Calculate average confidence
        double avgConfidence = nodes.stream()
            .mapToDouble(node -> node.getAiConfidence() != null ? node.getAiConfidence().doubleValue() : 0.0)
            .average()
            .orElse(0.0);
        mergedNode.setAiConfidence(BigDecimal.valueOf(avgConfidence));
        
        mergedNode.setMetaJson(bestNode.getMetaJson());
        
        return mergedNode;
    }
    
    /**
     * Assigns indices to nodes for ordering.
     */
    private void assignIndices(List<DocumentNode> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).setIdx(i);
        }
    }
    
    /**
     * Represents a group of similar nodes that should be merged.
     */
    private static class NodeGroup {
        private final List<DocumentNode> nodes = new ArrayList<>();
        
        public void addNode(DocumentNode node) {
            nodes.add(node);
        }
        
        public List<DocumentNode> getNodes() {
            return nodes;
        }
    }
}
