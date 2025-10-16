package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("ChunkedStructureService Comprehensive Tests")
class ChunkedStructureServiceComprehensiveTest {

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private LlmClient llmClient;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private DocumentChunkingConfig chunkingConfig;

    @InjectMocks
    private ChunkedStructureService service;

    private LlmClient.StructureOptions options;

    @BeforeEach
    void setUp() {
        options = LlmClient.StructureOptions.defaultOptions();
        
        // Default configuration
        lenient().when(chunkingConfig.getMaxSingleChunkTokens()).thenReturn(40_000);
        lenient().when(chunkingConfig.getMaxSingleChunkChars()).thenReturn(150_000);
        lenient().when(chunkingConfig.getOverlapTokens()).thenReturn(5_000);
        lenient().when(chunkingConfig.isAggressiveChunking()).thenReturn(false);
        
        // Default token estimation
        lenient().when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 4; // 1 token per 4 chars
        });
    }

    @Nested
    @DisplayName("processLargeDocument Tests")
    class ProcessLargeDocumentTests {

        @Test
        @DisplayName("when single chunk exceeds 1M chars then triggers emergency chunking")
        void processLargeDocument_singleChunkTooLarge_triggersEmergency() {
            // Given - document > 1M chars but chunker returns single chunk
            String hugeText = "x".repeat(1_500_000);
            DocumentChunker.DocumentChunk singleChunk = new DocumentChunker.DocumentChunk(
                    hugeText, 0, hugeText.length(), 0);
            
            List<DocumentChunker.DocumentChunk> emergencyChunks = List.of(
                    new DocumentChunker.DocumentChunk(hugeText.substring(0, 875_000), 0, 875_000, 0),
                    new DocumentChunker.DocumentChunk(hugeText.substring(787_500), 787_500, hugeText.length(), 1)
            );
            
            when(documentChunker.chunkDocument(hugeText, "doc-id")).thenReturn(List.of(singleChunk));
            
            // Mock generateStructureWithContext for emergency chunks
            DocumentNode node1 = createNode("Chapter 1", 0, 100);
            DocumentNode node2 = createNode("Chapter 2", 100, 200);
            
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(0), eq(2)))
                    .thenReturn(List.of(node1));
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(1), eq(2)))
                    .thenReturn(List.of(node2));

            // When
            List<DocumentNode> result = service.processLargeDocument(hugeText, options, "doc-id");

            // Then - lines 44-49 covered
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("when chunk processing throws non-LLM exception then propagates")
        void processLargeDocument_chunkProcessingFails_throwsException() {
            // Given - multiple chunks to trigger processChunksSequentialWithContext
            String text = "x".repeat(200_000);
            List<DocumentChunker.DocumentChunk> chunks = List.of(
                    new DocumentChunker.DocumentChunk(text.substring(0, 100_000), 0, 100_000, 0),
                    new DocumentChunker.DocumentChunk(text.substring(95_000), 95_000, text.length(), 1));
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(chunks);
            // Throw exception without LlmException cause (won't trigger fallback)
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("Network error"));

            // When & Then - line 126 covered
            assertThatThrownBy(() -> service.processLargeDocument(text, options, "doc-id"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Chunk processing failed");
        }

        @Test
        @DisplayName("when chunk returns no nodes then creates fallback node and continues")
        void processLargeDocument_noNodesGenerated_createsFallback() {
            // Given
            String text = "x".repeat(200_000);
            List<DocumentChunker.DocumentChunk> chunks = List.of(
                    new DocumentChunker.DocumentChunk(text.substring(0, 100_000), 0, 100_000, 0),
                    new DocumentChunker.DocumentChunk(text.substring(95_000), 95_000, text.length(), 1));
            
            LlmClient.LlmException llmException = new LlmClient.LlmException("No nodes generated for this text");
            RuntimeException wrappedException = new RuntimeException("Generation failed", llmException);
            
            DocumentNode node2 = createNode("Chapter 2", 0, 100);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(chunks);
            // First chunk fails with "No nodes generated", triggers fallback
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(0), eq(2)))
                    .thenThrow(wrappedException);
            // Second chunk succeeds  
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(1), eq(2)))
                    .thenReturn(List.of(node2));

            // When - lines 106-120 covered (fallback creation succeeds and processing continues)
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then - should have fallback node from chunk 1 + node from chunk 2
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("when small document with irrelevant nodes then filters them")
        void processLargeDocument_irrelevantNodes_filtersOut() {
            // Given
            String text = "Small document";
            DocumentChunker.DocumentChunk singleChunk = new DocumentChunker.DocumentChunk(text, 0, text.length(), 0);
            
            DocumentNode relevantNode = createNode("Chapter 1", 0, 100);
            DocumentNode irrelevantNode = createNode("About the Author", 100, 200);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(List.of(singleChunk));
            when(llmClient.generateStructure(text, options))
                    .thenReturn(List.of(relevantNode, irrelevantNode));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then - lines 201-202, 227-228 covered (filtering)
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Chapter 1");
        }

        @Test
        @DisplayName("when nodes have null offsets then adjusts without error")
        void processLargeDocument_nullOffsets_handlesGracefully() {
            // Given
            String text = "x".repeat(200_000);
            List<DocumentChunker.DocumentChunk> chunks = List.of(
                    new DocumentChunker.DocumentChunk(text, 0, 100_000, 0),
                    new DocumentChunker.DocumentChunk(text.substring(95_000), 95_000, text.length(), 1)
            );
            
            DocumentNode nodeWithNullStart = createNode("Chapter 1", 0, 100);
            nodeWithNullStart.setStartOffset(null); // Null start offset
            
            DocumentNode nodeWithNullEnd = createNode("Chapter 2", 0, 100);
            nodeWithNullEnd.setEndOffset(null); // Null end offset
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(chunks);
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(0), eq(2)))
                    .thenReturn(List.of(nodeWithNullStart));
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(1), eq(2)))
                    .thenReturn(List.of(nodeWithNullEnd));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then - lines 150, 153 branches covered
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("needsChunking Tests")
    class NeedsChunkingTests {

        @Test
        @DisplayName("when text is null then returns false")
        void needsChunking_nullText_returnsFalse() {
            // When
            boolean result = service.needsChunking(null);

            // Then - lines 243-244
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("when text is empty then returns false")
        void needsChunking_emptyText_returnsFalse() {
            // When
            boolean result = service.needsChunking("");

            // Then - lines 243-244
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("when text exceeds 1.25M chars then forces chunking")
        void needsChunking_exceeds1_25MChars_forcesChunking() {
            // Given
            String hugeText = "x".repeat(1_300_000);

            // When
            boolean result = service.needsChunking(hugeText);

            // Then - lines 249-251
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("when aggressive chunking enabled then uses lower limits")
        void needsChunking_aggressiveChunking_usesLowerLimits() {
            // Given
            String text = "x".repeat(100_000); // 100k chars = 25k tokens
            when(chunkingConfig.isAggressiveChunking()).thenReturn(true);

            // When
            boolean result = service.needsChunking(text);

            // Then - lines 259-261 covered
            assertThat(result).isFalse(); // Within 30k token limit when aggressive
        }

        @Test
        @DisplayName("when large doc not chunked then logs warning")
        void needsChunking_largeDocNotChunked_logsWarning() {
            // Given - 40k chars = 10k tokens (doesn't exceed 40k token limit but > 10k)
            String text = "x".repeat(40_000);

            // When
            boolean result = service.needsChunking(text);

            // Then - lines 278-279 covered
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("when exceeds token limit then needs chunking")
        void needsChunking_exceedsTokenLimit_returnsTrue() {
            // Given - text that exceeds token limit
            String text = "x".repeat(200_000); // 50k tokens > 40k limit

            // When
            boolean result = service.needsChunking(text);

            // Then - line 265 branch covered
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("when exceeds char limit then needs chunking")
        void needsChunking_exceedsCharLimit_returnsTrue() {
            // Given - text that exceeds char limit
            String text = "x".repeat(180_000); // 180k chars > 150k limit

            // When
            boolean result = service.needsChunking(text);

            // Then - line 266 branch covered
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("estimateChunkCount Tests")
    class EstimateChunkCountTests {

        @Test
        @DisplayName("when document needs chunking then estimates multiple chunks")
        void estimateChunkCount_needsChunking_estimatesMultiple() {
            // Given - large document
            String text = "x".repeat(200_000); // 50k tokens

            // When
            int count = service.estimateChunkCount(text);

            // Then - lines 295-300 covered
            assertThat(count).isGreaterThan(1);
        }

        @Test
        @DisplayName("when document does not need chunking then returns 1")
        void estimateChunkCount_doesNotNeedChunking_returns1() {
            // Given
            String text = "Small text";

            // When
            int count = service.estimateChunkCount(text);

            // Then - lines 290-291
            assertThat(count).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Filter and Irrelevant Content Tests")
    class FilterIrrelevantTests {

        @Test
        @DisplayName("when node title contains author then filters it out")
        void filterNodes_authorTitle_filtersOut() {
            // Given
            String text = "Text";
            DocumentChunker.DocumentChunk chunk = new DocumentChunker.DocumentChunk(text, 0, text.length(), 0);
            
            DocumentNode relevantNode = createNode("Chapter 1: Introduction", 0, 100);
            DocumentNode authorNode = createNode("About the Author", 100, 200);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(List.of(chunk));
            when(llmClient.generateStructure(text, options))
                    .thenReturn(List.of(relevantNode, authorNode));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then - line 228 covered (isQuizIrrelevant returns true)
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).contains("Chapter 1");
        }

        @Test
        @DisplayName("when node title contains acknowledgments then filters it out")
        void filterNodes_acknowledgmentsTitle_filtersOut() {
            // Given
            String text = "Text";
            DocumentChunker.DocumentChunk chunk = new DocumentChunker.DocumentChunk(text, 0, text.length(), 0);
            
            DocumentNode relevantNode = createNode("Chapter 1", 0, 100);
            DocumentNode ackNode = createNode("Acknowledgments", 100, 200);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(List.of(chunk));
            when(llmClient.generateStructure(text, options))
                    .thenReturn(List.of(relevantNode, ackNode));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Chapter 1");
        }

        @Test
        @DisplayName("when node title contains index then filters it out")
        void filterNodes_indexTitle_filtersOut() {
            // Given
            String text = "Text";
            DocumentChunker.DocumentChunk chunk = new DocumentChunker.DocumentChunk(text, 0, text.length(), 0);
            
            DocumentNode relevantNode = createNode("Main Content", 0, 100);
            DocumentNode indexNode = createNode("Index Section", 100, 200);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(List.of(chunk));
            when(llmClient.generateStructure(text, options))
                    .thenReturn(List.of(relevantNode, indexNode));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when node title contains bibliography then filters it out")
        void filterNodes_bibliographyTitle_filtersOut() {
            // Given
            String text = "Text";
            DocumentChunker.DocumentChunk chunk = new DocumentChunker.DocumentChunk(text, 0, text.length(), 0);
            
            DocumentNode relevantNode = createNode("Chapter Content", 0, 100);
            DocumentNode bibNode = createNode("Bibliography", 100, 200);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(List.of(chunk));
            when(llmClient.generateStructure(text, options))
                    .thenReturn(List.of(relevantNode, bibNode));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when all nodes are irrelevant then returns empty list")
        void filterNodes_allIrrelevant_returnsEmpty() {
            // Given
            String text = "Text";
            DocumentChunker.DocumentChunk chunk = new DocumentChunker.DocumentChunk(text, 0, text.length(), 0);
            
            DocumentNode author = createNode("About the Authors", 0, 100);
            DocumentNode ack = createNode("Acknowledgments", 100, 200);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(List.of(chunk));
            when(llmClient.generateStructure(text, options))
                    .thenReturn(List.of(author, ack));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("when LlmException without no nodes message then propagates")
        void processChunks_llmExceptionDifferentMessage_throwsException() {
            // Given - use 2 chunks to ensure processChunksSequentialWithContext path
            String text = "x".repeat(200_000);
            List<DocumentChunker.DocumentChunk> chunks = List.of(
                    new DocumentChunker.DocumentChunk(text.substring(0, 100_000), 0, 100_000, 0),
                    new DocumentChunker.DocumentChunk(text.substring(95_000), 95_000, text.length(), 1)
            );
            
            // Throw LlmException with message that doesn't contain "No nodes generated"
            LlmClient.LlmException llmException = new LlmClient.LlmException("API rate limit exceeded");
            RuntimeException wrappedException = new RuntimeException("Failed", llmException);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(chunks);
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), anyInt(), anyInt()))
                    .thenThrow(wrappedException);

            // When & Then - line 126 covered (LlmException without "No nodes generated" propagates)
            assertThatThrownBy(() -> service.processLargeDocument(text, options, "doc-id"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Chunk processing failed");
        }

        @Test
        @DisplayName("when fallback succeeds then processing continues")
        void processChunks_fallbackSucceeds_continuesProcessing() {
            // Given - chunk with text to test fallback creation
            String text = "x".repeat(200_000);
            List<DocumentChunker.DocumentChunk> chunks = List.of(
                    new DocumentChunker.DocumentChunk(text.substring(0, 100_000), 0, 100_000, 0),
                    new DocumentChunker.DocumentChunk(text.substring(95_000), 95_000, text.length(), 1)
            );
            
            LlmClient.LlmException llmException = new LlmClient.LlmException("No nodes generated");
            RuntimeException wrappedException = new RuntimeException("Failed", llmException);
            
            DocumentNode node2 = createNode("Chapter 2", 0, 100);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(chunks);
            // First chunk fails, triggers fallback
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(0), eq(2)))
                    .thenThrow(wrappedException);
            // Second chunk succeeds
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(1), eq(2)))
                    .thenReturn(List.of(node2));

            // When - lines 110-120 covered (fallback succeeds)
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then - fallback node + successful node
            assertThat(result).hasSize(2);
            // First node is the fallback
            assertThat(result.get(0).getTitle()).contains("Chunk 1");
            // Second node is from successful processing
            assertThat(result.get(1).getTitle()).isEqualTo("Chapter 2");
        }
    }

    @Nested
    @DisplayName("Offset Adjustment Tests")
    class OffsetAdjustmentTests {

        @Test
        @DisplayName("when processing multiple chunks then adjusts offsets correctly")
        void processChunks_multipleChunks_adjustsOffsets() {
            // Given
            String text = "x".repeat(250_000);
            List<DocumentChunker.DocumentChunk> chunks = List.of(
                    new DocumentChunker.DocumentChunk(text.substring(0, 100_000), 0, 100_000, 0),
                    new DocumentChunker.DocumentChunk(text.substring(95_000, 200_000), 95_000, 200_000, 1),
                    new DocumentChunker.DocumentChunk(text.substring(195_000), 195_000, text.length(), 2)
            );
            
            // Nodes returned with chunk-relative offsets
            DocumentNode node1 = createNode("Chapter 1", 0, 50);
            DocumentNode node2 = createNode("Chapter 2", 0, 50);
            DocumentNode node3 = createNode("Chapter 3", 0, 50);
            
            when(documentChunker.chunkDocument(text, "doc-id")).thenReturn(chunks);
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(0), eq(3)))
                    .thenReturn(List.of(node1));
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(1), eq(3)))
                    .thenReturn(List.of(node2));
            when(llmClient.generateStructureWithContext(anyString(), any(), anyList(), eq(2), eq(3)))
                    .thenReturn(List.of(node3));

            // When
            List<DocumentNode> result = service.processLargeDocument(text, options, "doc-id");

            // Then - verify offsets were adjusted to global coordinates
            assertThat(result).hasSize(3);
            assertThat(result.get(0).getStartOffset()).isEqualTo(0); // Chunk 0 offset + 0
            assertThat(result.get(1).getStartOffset()).isEqualTo(95_000); // Chunk 1 offset + 0
            assertThat(result.get(2).getStartOffset()).isEqualTo(195_000); // Chunk 2 offset + 0
        }
    }

    @Nested
    @DisplayName("getChunkingConfig Tests")
    class GetChunkingConfigTests {

        @Test
        @DisplayName("when called then returns config")
        void getChunkingConfig_returnsConfigObject() {
            // When
            DocumentChunkingConfig result = service.getChunkingConfig();

            // Then - line 333
            assertThat(result).isEqualTo(chunkingConfig);
        }
    }

    // Helper methods

    private DocumentNode createNode(String title, int start, int end) {
        DocumentNode node = new DocumentNode();
        node.setTitle(title);
        node.setStartOffset(start);
        node.setEndOffset(end);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setDepth((short) 0);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return node;
    }
}

