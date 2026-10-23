package uk.gegc.quizmaker.features.documentProcess.application;

import uk.gegc.quizmaker.features.documentProcess.application.impl.StructureServiceImpl;

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
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for StructureService validation and parent relationship methods.
 * Targets uncovered lines in validateNodes, assignParentRelationships, and findBestParent.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("StructureService Validation Tests")
class StructureServiceValidationTest {

    @Mock
    private DocumentNodeRepository nodeRepository;

    @Mock
    private NormalizedDocumentRepository documentRepository;

    @Mock
    private DocumentNodeMapper nodeMapper;

    @Mock
    private LlmClient llmClient;

    @Mock
    private AnchorOffsetCalculator anchorOffsetCalculator;

    @Mock
    private NodeHierarchyBuilder hierarchyBuilder;

    @Mock
    private DocumentQueryService queryService;

    @Mock
    private ChunkedStructureService chunkedStructureService;

    @org.mockito.Spy
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate = new ImmediateTransactionTemplate();
    @org.mockito.Spy
    private java.time.Clock clock = java.time.Clock.systemUTC();

    @InjectMocks
    private StructureServiceImpl service;

    private UUID documentId;
    private NormalizedDocument document;
    private DocumentChunkingConfig chunkingConfig;

    @BeforeEach
    void setUp() {
        GuardedLlmClientDefaults.enable(llmClient);
        org.mockito.Mockito.lenient().when(documentRepository.publishStructureVersion(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        org.mockito.Mockito.lenient().when(documentRepository.getReferenceById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> documentRepository.findById(call.getArgument(0)).orElseThrow());
        documentId = UUID.randomUUID();
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("test.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        // Make document longer to accommodate node offsets
        document.setNormalizedText("Sample document text for testing. ".repeat(10)); // ~340 chars
        document.setCharCount(document.getNormalizedText().length());

        // Setup chunking config
        chunkingConfig = new DocumentChunkingConfig();
        chunkingConfig.setMaxSingleChunkChars(150_000);
        chunkingConfig.setMaxSingleChunkTokens(40_000);
        
        lenient().when(chunkedStructureService.getChunkingConfig()).thenReturn(chunkingConfig);
    }

    @Nested
    @DisplayName("validateNodes Tests")
    class ValidateNodesTests {

        @Test
        @DisplayName("validateNodes: when node has null endAnchor then throws exception")
        void validateNodes_nullEndAnchor_throwsException() {
            // Given
            DocumentNode nodeWithNullEndAnchor = createNodeWithNullEndAnchor();
            // Set offsets within document bounds
            nodeWithNullEndAnchor.setStartOffset(0);
            nodeWithNullEndAnchor.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithNullEndAnchor);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes); // Return as-is with offsets already set

            // When & Then - Line 460-461 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected error during structure building")
                    .hasRootCauseMessage("Node end anchor is required");
        }

        @Test
        @DisplayName("validateNodes: when node has blank endAnchor then throws exception")
        void validateNodes_blankEndAnchor_throwsException() {
            // Given
            DocumentNode nodeWithBlankEndAnchor = createNodeWithBlankEndAnchor();
            nodeWithBlankEndAnchor.setStartOffset(0);
            nodeWithBlankEndAnchor.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithBlankEndAnchor);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);

            // When & Then - Line 460-461 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Node end anchor is required");
        }

        @Test
        @DisplayName("validateNodes: when node has null type then throws exception")
        void validateNodes_nullType_throwsException() {
            // Given
            DocumentNode nodeWithNullType = createNodeWithNullType();
            nodeWithNullType.setStartOffset(0);
            nodeWithNullType.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithNullType);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);

            // When & Then - Line 465-466 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Node type is required");
        }

        @Test
        @DisplayName("validateNodes: when node has null depth then throws exception during grouping")
        void validateNodes_nullDepth_throwsException() {
            // Given
            List<DocumentNode> nodes = List.of(createNodeWithNullDepth());
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            lenient().when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            lenient().when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenAnswer(inv -> {
                        List<DocumentNode> n = inv.getArgument(0);
                        n.forEach(node -> {
                            node.setStartOffset(0);
                            node.setEndOffset(100);
                        });
                        return n;
                    });

            // When & Then - Null depth causes NPE during groupBy, but if validation ran it would hit line 471-472
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected error during structure building");
        }

        @Test
        @DisplayName("validateNodes: when node has negative depth then throws exception")
        void validateNodes_negativeDepth_throwsException() {
            // Given
            DocumentNode nodeWithNegativeDepth = createNodeWithNegativeDepth();
            nodeWithNegativeDepth.setStartOffset(0);
            nodeWithNegativeDepth.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithNegativeDepth);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);

            // When & Then - Line 471-472 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Node depth must be non-negative");
        }
    }

    @Nested
    class ParentRelationshipTests {
        @Test
        void assignsDeepestContainingParentAndRestartsSiblingIndicesPerParent() {
            var rootA = createValidNode("rootA", 0, 100, (short) 0);
            var rootB = createValidNode("rootB", 100, 200, (short) 0);
            var sectionA = createValidNode("sectionA", 10, 90, (short) 1);
            var childA = createValidNode("childA", 20, 40, (short) 2);
            var childA2 = createValidNode("childA2", 40, 80, (short) 2);
            var childB = createValidNode("childB", 110, 190, (short) 1);
            var orphan = createValidNode("orphan", 210, 220, (short) 1);
            var original = List.of(rootA, rootB, sectionA, childA, childA2, childB, orphan);
            when(llmClient.generateStructure(anyString(), any())).thenReturn(original);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenAnswer(call -> {
                List<DocumentNode> level = call.getArgument(0);
                for (DocumentNode copy : level) {
                    var position = original.stream().filter(n -> n.getTitle().equals(copy.getTitle()))
                            .findFirst().orElseThrow();
                    copy.setStartOffset(position.getStartOffset());
                    copy.setEndOffset(position.getEndOffset());
                }
                return level;
            });
            var source = new StructureBuildSource(documentId, null, 0, "x".repeat(220), 220);
            var prepared = service.prepare(source, LlmClient.StructureOptions.defaultOptions(), () -> { });
            var byTitle = prepared.stream().collect(java.util.stream.Collectors.toMap(DocumentNode::getTitle, n -> n));
            assertThat(byTitle.get("sectionA").getParent()).isSameAs(byTitle.get("rootA"));
            assertThat(byTitle.get("childA").getParent()).isSameAs(byTitle.get("sectionA"));
            assertThat(byTitle.get("childA2").getParent()).isSameAs(byTitle.get("sectionA"));
            assertThat(byTitle.get("childA").getIdx()).isZero();
            assertThat(byTitle.get("childA2").getIdx()).isEqualTo(1);
            assertThat(byTitle.get("childB").getParent()).isSameAs(byTitle.get("rootB"));
            assertThat(byTitle.get("childB").getIdx()).isZero();
            assertThat(byTitle.get("orphan").getParent()).isNull();
            assertThat(byTitle.get("rootA").getIdx()).isZero();
            assertThat(byTitle.get("rootB").getIdx()).isEqualTo(1);
            verifyNoInteractions(nodeRepository, documentRepository);
        }
    }

    private DocumentNode createValidNode(String title, int startOffset, int endOffset, short depth) {
        DocumentNode node = new DocumentNode();
        node.setTitle(title);
        node.setType(DocumentNode.NodeType.SECTION);
        node.setStartAnchor("Start: " + title);
        node.setEndAnchor("End: " + title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setDepth(depth);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return node;
    }
    
    private void setNodeOffsetsWithinBounds(List<DocumentNode> nodes, int docLength) {
        // Set offsets for nodes ensuring they fit within document bounds
        int segmentSize = Math.max(10, docLength / Math.max(1, nodes.size()));
        for (int i = 0; i < nodes.size(); i++) {
            int start = i * segmentSize;
            int end = Math.min(start + segmentSize, docLength - 1);
            nodes.get(i).setStartOffset(start);
            nodes.get(i).setEndOffset(end);
        }
    }

    private DocumentNode createNodeWithNullEndAnchor() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setEndAnchor(null);
        return node;
    }

    private DocumentNode createNodeWithBlankEndAnchor() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setEndAnchor("   ");
        return node;
    }

    private DocumentNode createNodeWithNullType() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setType(null);
        return node;
    }

    private DocumentNode createNodeWithNullDepth() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setDepth(null);
        return node;
    }

    private DocumentNode createNodeWithNegativeDepth() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setDepth((short) -5);
        return node;
    }
}
