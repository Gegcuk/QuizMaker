package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NodeMerger Offset Calculation Tests")
class NodeMergerOffsetTest {

    private final NodeMerger nodeMerger = new NodeMerger();

    @Test
    @DisplayName("Should correctly adjust offsets when merging chunks")
    void shouldCorrectlyAdjustOffsetsWhenMergingChunks() {
        // Given: Two chunks with nodes that have chunk-relative offsets
        // Chunk 1: positions 0-1000 in document
        // Chunk 2: positions 500-1500 in document (with overlap)
        
        DocumentChunker.DocumentChunk chunk1 = new DocumentChunker.DocumentChunk("chunk1 text", 0, 1000, 0);
        DocumentChunker.DocumentChunk chunk2 = new DocumentChunker.DocumentChunk("chunk2 text", 500, 1500, 1);
        
        // Create nodes with chunk-relative offsets
        DocumentNode node1FromChunk1 = createNode("Chapter 1", 0, 500, "Chapter 1", "end of chapter 1");
        DocumentNode node2FromChunk1 = createNode("Section 1.1", 100, 300, "Section 1.1", "end of section 1.1");
        
        DocumentNode node1FromChunk2 = createNode("Section 1.2", 0, 400, "Section 1.2", "end of section 1.2");
        DocumentNode node2FromChunk2 = createNode("Chapter 2", 200, 800, "Chapter 2", "end of chapter 2");
        
        List<List<DocumentNode>> chunkResults = Arrays.asList(
            Arrays.asList(node1FromChunk1, node2FromChunk1),  // Chunk 1 results
            Arrays.asList(node1FromChunk2, node2FromChunk2)   // Chunk 2 results
        );
        
        List<DocumentChunker.DocumentChunk> chunks = Arrays.asList(chunk1, chunk2);
        
        // When: Merging the chunks
        List<DocumentNode> mergedNodes = nodeMerger.mergeChunkNodes(chunkResults, chunks);
        
        // Then: Offsets should be correctly adjusted to document-relative coordinates
        assertThat(mergedNodes).hasSize(4);
        
        // Node from chunk 1 should have original offsets (chunk starts at 0)
        DocumentNode adjustedNode1 = findNodeByTitle(mergedNodes, "Chapter 1");
        assertThat(adjustedNode1.getStartOffset()).isEqualTo(0);
        assertThat(adjustedNode1.getEndOffset()).isEqualTo(500);
        
        DocumentNode adjustedNode2 = findNodeByTitle(mergedNodes, "Section 1.1");
        assertThat(adjustedNode2.getStartOffset()).isEqualTo(100);
        assertThat(adjustedNode2.getEndOffset()).isEqualTo(300);
        
        // Node from chunk 2 should have offsets adjusted by chunk start position (500)
        DocumentNode adjustedNode3 = findNodeByTitle(mergedNodes, "Section 1.2");
        assertThat(adjustedNode3.getStartOffset()).isEqualTo(500); // 0 + 500
        assertThat(adjustedNode3.getEndOffset()).isEqualTo(900);   // 400 + 500
        
        DocumentNode adjustedNode4 = findNodeByTitle(mergedNodes, "Chapter 2");
        assertThat(adjustedNode4.getStartOffset()).isEqualTo(700); // 200 + 500
        assertThat(adjustedNode4.getEndOffset()).isEqualTo(1300);  // 800 + 500
    }
    
    @Test
    @DisplayName("Should handle overlapping nodes correctly")
    void shouldHandleOverlappingNodesCorrectly() {
        // Given: Two chunks with overlapping nodes
        DocumentChunker.DocumentChunk chunk1 = new DocumentChunker.DocumentChunk("chunk1", 0, 1000, 0);
        DocumentChunker.DocumentChunk chunk2 = new DocumentChunker.DocumentChunk("chunk2", 500, 1500, 1);
        
        // Create overlapping nodes (same section detected in both chunks)
        DocumentNode node1FromChunk1 = createNode("Overlapping Section", 400, 600, "Overlapping Section", "end of section");
        DocumentNode node1FromChunk2 = createNode("Overlapping Section", 0, 200, "Overlapping Section", "end of section");
        
        List<List<DocumentNode>> chunkResults = Arrays.asList(
            Arrays.asList(node1FromChunk1),
            Arrays.asList(node1FromChunk2)
        );
        
        List<DocumentChunker.DocumentChunk> chunks = Arrays.asList(chunk1, chunk2);
        
        // When: Merging the chunks
        List<DocumentNode> mergedNodes = nodeMerger.mergeChunkNodes(chunkResults, chunks);
        
        // Then: Overlapping nodes should be merged into one with expanded boundaries
        assertThat(mergedNodes).hasSize(1);
        
        DocumentNode mergedNode = mergedNodes.get(0);
        assertThat(mergedNode.getTitle()).isEqualTo("Overlapping Section");
        assertThat(mergedNode.getStartOffset()).isEqualTo(400); // min of both start offsets
        assertThat(mergedNode.getEndOffset()).isEqualTo(700);   // max of both end offsets (200 + 500)
    }
    
    private DocumentNode createNode(String title, int startOffset, int endOffset, String startAnchor, String endAnchor) {
        DocumentNode node = new DocumentNode();
        node.setTitle(title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setStartAnchor(startAnchor);
        node.setEndAnchor(endAnchor);
        node.setType(DocumentNode.NodeType.SECTION);
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return node;
    }
    
    private DocumentNode findNodeByTitle(List<DocumentNode> nodes, String title) {
        return nodes.stream()
            .filter(node -> title.equals(node.getTitle()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Node with title '" + title + "' not found"));
    }
}
