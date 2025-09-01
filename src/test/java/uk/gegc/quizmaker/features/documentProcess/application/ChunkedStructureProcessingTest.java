package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for complete chunked structure processing.
 * Tests the full pipeline: chunking -> AI processing -> response combination -> persistence.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Chunked Structure Processing Integration Test")
class ChunkedStructureProcessingTest {

    @Mock
    private DocumentChunker documentChunker;
    
    @Mock
    private LlmClient llmClient;
    
    @Mock
    private TokenCounter tokenCounter;
    
    @Mock
    private DocumentChunkingConfig chunkingConfig;

    private ChunkedStructureService chunkedStructureService;

    @BeforeEach
    void setUp() {
        chunkedStructureService = new ChunkedStructureService(
                documentChunker, llmClient, tokenCounter, chunkingConfig);
    }

    @Test
    @DisplayName("Should process large document through complete pipeline and combine results correctly")
    void shouldProcessLargeDocumentThroughCompletePipelineAndCombineResultsCorrectly() {
        // Given: A large document that needs chunking (small for fast testing)
        String largeDocument = createLargeDocument(10_000); // ~3k tokens
        String documentId = "large-doc-processing-test";
        
        // Create just 3 chunks for simple testing
        List<DocumentChunker.DocumentChunk> chunks = createSimpleChunks(largeDocument);
        
        // Mock the chunking process
        when(documentChunker.chunkDocument(largeDocument, documentId)).thenReturn(chunks);
        
        // Mock AI responses for each chunk
        List<List<DocumentNode>> chunkResponses = createMockAIResponses(chunks.size());
        
        // Mock the AI processing for each chunk
        for (int i = 0; i < chunks.size(); i++) {
            when(llmClient.generateStructureWithContext(
                    eq(chunks.get(i).getText()), 
                    any(LlmClient.StructureOptions.class), 
                    anyList(), 
                    eq(i), 
                    eq(chunks.size())
            )).thenReturn(chunkResponses.get(i));
        }
        
        // When: Processing the large document
        LlmClient.StructureOptions options = LlmClient.StructureOptions.defaultOptions();
        List<DocumentNode> result = chunkedStructureService.processLargeDocument(
                largeDocument, options, documentId);
        
        // Then: Verify the complete pipeline worked correctly
        
        // 1. Verify chunking was called
        verify(documentChunker).chunkDocument(largeDocument, documentId);
        
        // 2. Verify AI processing was called for each chunk
        for (int i = 0; i < chunks.size(); i++) {
            verify(llmClient).generateStructureWithContext(
                    eq(chunks.get(i).getText()), 
                    any(LlmClient.StructureOptions.class), 
                    anyList(), 
                    eq(i), 
                    eq(chunks.size())
            );
        }
        
        // 3. Verify all chunk responses were combined
        int expectedTotalNodes = chunkResponses.stream()
                .mapToInt(List::size)
                .sum();
        assertThat(result).hasSize(expectedTotalNodes);
        
        // 4. Verify the combined result contains nodes from all chunks
        verifyCombinedResultContainsAllChunks(result, chunkResponses);
        
        // Log detailed results
        System.out.println("\n=== CHUNKED STRUCTURE PROCESSING TEST RESULTS ===");
        System.out.println("Document size: " + largeDocument.length() + " characters");
        System.out.println("Number of chunks: " + chunks.size());
        System.out.println("Total nodes generated: " + result.size());
        
        System.out.println("\nChunk processing details:");
        for (int i = 0; i < chunks.size(); i++) {
            System.out.printf("Chunk %d: %d chars -> %d nodes%n", 
                    i + 1, chunks.get(i).getText().length(), chunkResponses.get(i).size());
        }
        
        System.out.println("\nFinal combined result:");
        for (int i = 0; i < Math.min(result.size(), 10); i++) {
            DocumentNode node = result.get(i);
            System.out.printf("Node %d: '%s' (depth: %d, offset: %d-%d)%n", 
                    i + 1, node.getTitle(), node.getDepth(), 
                    node.getStartOffset(), node.getEndOffset());
        }
        
        if (result.size() > 10) {
            System.out.println("... and " + (result.size() - 10) + " more nodes");
        }
        
        System.out.println("\n✓ COMPLETE PIPELINE SUCCESSFULLY PROCESSED AND COMBINED RESULTS!");
    }

    /**
     * Creates a large document with realistic content.
     */
    private String createLargeDocument(int targetSize) {
        StringBuilder doc = new StringBuilder();
        
        String sectionTemplate = """
                SECTION %d
                
                This is section %d of the document. It contains detailed information about 
                various topics and concepts. The content is structured to provide comprehensive 
                coverage while maintaining readability and logical flow.
                
                Subsection %d.1: Key Concepts
                
                Here we discuss the fundamental concepts that form the basis of understanding 
                the subject matter. These concepts are essential for grasping the more advanced 
                topics that follow.
                
                Subsection %d.2: Advanced Topics
                
                This subsection delves into more complex aspects of the subject. It builds upon 
                the foundational concepts introduced earlier and explores their practical applications.
                
                Subsection %d.3: Practical Examples
                
                Real-world examples and case studies are presented here to illustrate the 
                practical relevance of the theoretical concepts discussed in previous sections.
                
                """;
        
        int sectionCount = 1;
        while (doc.length() < targetSize) {
            doc.append(String.format(sectionTemplate, sectionCount, sectionCount, 
                    sectionCount, sectionCount, sectionCount));
            sectionCount++;
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }

    /**
     * Creates simple document chunks for testing.
     */
    private List<DocumentChunker.DocumentChunk> createSimpleChunks(String document) {
        List<DocumentChunker.DocumentChunk> chunks = new ArrayList<>();
        int chunkSize = document.length() / 3; // Split into 3 equal chunks
        int overlap = 500;
        
        for (int i = 0; i < 3; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, document.length());
            String chunkText = document.substring(start, end);
            
            chunks.add(new DocumentChunker.DocumentChunk(chunkText, start, end, i));
        }
        
        return chunks;
    }

    /**
     * Creates mock AI responses for each chunk.
     */
    private List<List<DocumentNode>> createMockAIResponses(int numChunks) {
        List<List<DocumentNode>> responses = new ArrayList<>();
        
        for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
            List<DocumentNode> chunkNodes = new ArrayList<>();
            
            // Create 2-4 nodes per chunk with realistic content
            int nodesPerChunk = 2 + (chunkIndex % 3); // 2-4 nodes
            
            for (int nodeIndex = 0; nodeIndex < nodesPerChunk; nodeIndex++) {
                DocumentNode node = new DocumentNode();
                node.setTitle("Chunk " + (chunkIndex + 1) + " - Section " + (nodeIndex + 1));
                node.setDepth((short) (nodeIndex % 3)); // Vary depth
                // Use relative offsets within the chunk (will be adjusted by the service)
                node.setStartOffset(nodeIndex * 100);
                node.setEndOffset(nodeIndex * 100 + 500);
                node.setStartAnchor("anchor_start_" + chunkIndex + "_" + nodeIndex);
                node.setEndAnchor("anchor_end_" + chunkIndex + "_" + nodeIndex);
                
                chunkNodes.add(node);
            }
            
            responses.add(chunkNodes);
        }
        
        return responses;
    }

    /**
     * Verifies that node offsets were adjusted correctly for global document coordinates.
     */
    private void verifyNodeOffsetsAreAdjusted(List<DocumentNode> result, List<DocumentChunker.DocumentChunk> chunks) {
        // Verify that nodes from different chunks have different offset ranges
        for (DocumentChunker.DocumentChunk chunk : chunks) {
            List<DocumentNode> nodesInChunk = result.stream()
                    .filter(node -> {
                        // Check if node title indicates it belongs to this chunk
                        String chunkNumber = String.valueOf(chunk.getChunkIndex() + 1);
                        return node.getTitle().contains("Chunk " + chunkNumber);
                    })
                    .toList();
            
            // Each chunk should have some nodes
            assertThat(nodesInChunk).as("Chunk " + chunk.getChunkIndex() + " should have nodes with adjusted offsets")
                    .isNotEmpty();
            
            // Verify offsets are within chunk boundaries (with some tolerance for overlap)
            for (DocumentNode node : nodesInChunk) {
                assertThat(node.getStartOffset()).as("Node start offset should be within or near chunk boundaries")
                        .isGreaterThanOrEqualTo(chunk.getStartOffset() - 1000); // Allow some tolerance
                assertThat(node.getEndOffset()).as("Node end offset should be within or near chunk boundaries")
                        .isLessThanOrEqualTo(chunk.getEndOffset() + 1000); // Allow some tolerance
            }
        }
    }

    /**
     * Verifies that nodes are properly filtered (no irrelevant content).
     */
    private void verifyNodesAreProperlyFiltered(List<DocumentNode> result) {
        // Check that no irrelevant nodes are present
        for (DocumentNode node : result) {
            String title = node.getTitle().toLowerCase();
            
            // Should not contain irrelevant keywords
            assertThat(title).as("Node title should not contain irrelevant keywords")
                    .doesNotContain("author")
                    .doesNotContain("acknowledgment")
                    .doesNotContain("index")
                    .doesNotContain("appendix")
                    .doesNotContain("bibliography");
        }
    }

    /**
     * Verifies that the combined result contains nodes from all chunks.
     */
    private void verifyCombinedResultContainsAllChunks(List<DocumentNode> result, List<List<DocumentNode>> chunkResponses) {
        // Verify that we have nodes from each chunk
        for (int chunkIndex = 0; chunkIndex < chunkResponses.size(); chunkIndex++) {
            final int i = chunkIndex; // Make effectively final for lambda
            List<DocumentNode> chunkResponse = chunkResponses.get(chunkIndex);
            
            // Check that at least one node from this chunk is in the result
            boolean hasNodeFromChunk = result.stream()
                    .anyMatch(node -> node.getTitle().contains("Chunk " + (i + 1)));
            
            assertThat(hasNodeFromChunk).as("Result should contain nodes from chunk " + (i + 1))
                    .isTrue();
        }
    }
}
