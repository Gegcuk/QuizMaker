package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Chunked Structure Service Tests")
class ChunkedStructureServiceTest {

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private LlmClient llmClient;

    @Mock
    private NodeMerger nodeMerger;

    @InjectMocks
    private ChunkedStructureService service;

    private LlmClient.StructureOptions options;
    private String documentId;

    @BeforeEach
    void setUp() {
        options = LlmClient.StructureOptions.defaultOptions();
        documentId = "test-doc-123";
    }

    @Test
    @DisplayName("Should process small document without chunking")
    void shouldProcessSmallDocumentWithoutChunking() {
        // Given
        String smallText = "This is a small document with less than 250k characters.";
        List<DocumentNode> expectedNodes = createTestNodes();
        List<DocumentChunker.DocumentChunk> singleChunk = List.of(
            new DocumentChunker.DocumentChunk(smallText, 0, smallText.length(), 0)
        );

        when(documentChunker.chunkDocument(smallText, documentId)).thenReturn(singleChunk);
        when(llmClient.generateStructure(smallText, options)).thenReturn(expectedNodes);

        // When
        List<DocumentNode> result = service.processLargeDocument(smallText, options, documentId);

        // Then
        assertThat(result).isEqualTo(expectedNodes);
    }

    @Test
    @DisplayName("Should process large document with chunking")
    void shouldProcessLargeDocumentWithChunking() {
        // Given
        String largeText = createLargeDocument();
        List<DocumentChunker.DocumentChunk> chunks = createTestChunks();
        List<List<DocumentNode>> chunkResults = createChunkResults();
        List<DocumentNode> expectedMergedNodes = createTestNodes();

        when(documentChunker.chunkDocument(largeText, documentId)).thenReturn(chunks);
        when(llmClient.generateStructure(anyString(), eq(options))).thenReturn(chunkResults.get(0), chunkResults.get(1));
        when(nodeMerger.mergeChunkNodes(anyList(), eq(chunks))).thenReturn(expectedMergedNodes);

        // When
        List<DocumentNode> result = service.processLargeDocument(largeText, options, documentId);

        // Then
        assertThat(result).isEqualTo(expectedMergedNodes);
    }

    @Test
    @DisplayName("Should correctly identify documents that need chunking")
    void shouldCorrectlyIdentifyDocumentsThatNeedChunking() {
        // Given
        String smallText = "Small document";
        String largeText = createLargeDocument();

        // When & Then
        assertThat(service.needsChunking(smallText)).isFalse();
        assertThat(service.needsChunking(largeText)).isTrue();
    }

    @Test
    @DisplayName("Should estimate correct chunk count")
    void shouldEstimateCorrectChunkCount() {
        // Given
        String smallText = "Small document";
        String largeText = createLargeDocument();

        // When & Then
        assertThat(service.estimateChunkCount(smallText)).isEqualTo(1);
        assertThat(service.estimateChunkCount(largeText)).isGreaterThan(1);
    }

    // Helper methods

    private String createLargeDocument() {
        // Create a document larger than MAX_CHUNK_SIZE
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300_000; i++) {
            sb.append("This is a large document that needs to be chunked. ");
        }
        return sb.toString();
    }

    private List<DocumentChunker.DocumentChunk> createTestChunks() {
        DocumentChunker.DocumentChunk chunk1 = new DocumentChunker.DocumentChunk(
            "First chunk content", 0, 100, 0);
        DocumentChunker.DocumentChunk chunk2 = new DocumentChunker.DocumentChunk(
            "Second chunk content", 90, 200, 1);
        return Arrays.asList(chunk1, chunk2);
    }

    private List<List<DocumentNode>> createChunkResults() {
        List<DocumentNode> nodes1 = Arrays.asList(createNode("Node 1", 0, 50));
        List<DocumentNode> nodes2 = Arrays.asList(createNode("Node 2", 100, 150));
        return Arrays.asList(nodes1, nodes2);
    }

    private List<DocumentNode> createTestNodes() {
        return Arrays.asList(
            createNode("Chapter 1", 0, 100),
            createNode("Section 1.1", 10, 50),
            createNode("Section 1.2", 60, 90)
        );
    }

    private DocumentNode createNode(String title, int start, int end) {
        DocumentNode node = new DocumentNode();
        node.setTitle(title);
        node.setStartOffset(start);
        node.setEndOffset(end);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return node;
    }
}
