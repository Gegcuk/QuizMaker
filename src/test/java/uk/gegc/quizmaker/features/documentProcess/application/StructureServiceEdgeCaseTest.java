package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StructureServiceEdgeCaseTest {

    @Mock
    private OpenAiLlmClient llmClient;

    @Mock
    private AnchorOffsetCalculator anchorOffsetCalculator;

    @Mock
    private DocumentNodeRepository nodeRepository;

    @Mock
    private NormalizedDocumentRepository documentRepository;

    @Mock
    private NodeHierarchyBuilder hierarchyBuilder;

    @InjectMocks
    private StructureService service;

    private UUID documentId;
    private NormalizedDocument document;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("edge-case-test.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setNormalizedText(createTestDocumentText());
        document.setCharCount(createTestDocumentText().length());
    }

    @Test
    @DisplayName("Should handle nodes with zero confidence scores")
    void shouldHandleNodesWithZeroConfidenceScores() {
        // Given
        List<DocumentNode> lowConfidenceNodes = createLowConfidenceNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(lowConfidenceNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(lowConfidenceNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(lowConfidenceNodes);
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should process nodes regardless of confidence
        verify(nodeRepository, times(2)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle nodes with extremely high confidence scores")
    void shouldHandleNodesWithExtremelyHighConfidenceScores() {
        // Given
        List<DocumentNode> highConfidenceNodes = createHighConfidenceNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(highConfidenceNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(highConfidenceNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(highConfidenceNodes);
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should process nodes regardless of confidence
        verify(nodeRepository, times(2)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle nodes with very long titles")
    void shouldHandleNodesWithVeryLongTitles() {
        // Given
        List<DocumentNode> longTitleNodes = createLongTitleNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(longTitleNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(longTitleNodes);

        // When
        service.buildStructure(documentId);

        // Then - Should handle long titles without issues
        verify(nodeRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle nodes with empty titles")
    void shouldHandleNodesWithEmptyTitles() {
        // Given
        List<DocumentNode> emptyTitleNodes = createEmptyTitleNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(emptyTitleNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");
    }

    @Test
    @DisplayName("Should handle nodes with whitespace-only titles")
    void shouldHandleNodesWithWhitespaceOnlyTitles() {
        // Given
        List<DocumentNode> whitespaceTitleNodes = createWhitespaceTitleNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(whitespaceTitleNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");
    }

    @Test
    @DisplayName("Should handle nodes with very deep nesting (10+ levels)")
    void shouldHandleNodesWithVeryDeepNesting() {
        // Given
        List<DocumentNode> deepNestedNodes = createVeryDeepNestedNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(deepNestedNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(deepNestedNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(deepNestedNodes);
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should handle deep nesting
        verify(nodeRepository, times(10)).saveAll(anyList()); // 10 depth levels
    }

    @Test
    @DisplayName("Should handle nodes with mixed depth ordering")
    void shouldHandleNodesWithMixedDepthOrdering() {
        // Given
        List<DocumentNode> mixedDepthNodes = createMixedDepthOrderingNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(mixedDepthNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(mixedDepthNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(mixedDepthNodes);
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should process in correct depth order
        verify(nodeRepository, times(3)).saveAll(anyList()); // 3 depth levels
    }

    @Test
    @DisplayName("Should handle nodes with overlapping offsets gracefully")
    void shouldHandleNodesWithOverlappingOffsets() {
        // Given
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(createTestNodes());
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100); // All nodes have same range
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(createTestNodes());
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(createTestNodes());
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doThrow(new RuntimeException("Sibling overlap detected")).when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When - Should not throw exception due to resilient validation
        service.buildStructure(documentId);

        // Then - Verify that nodes were still saved despite validation failure
        verify(nodeRepository, times(3)).saveAll(anyList()); // 3 depth levels
        verify(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());
    }

    @Test
    @DisplayName("Should handle nodes with boundary offset values")
    void shouldHandleNodesWithBoundaryOffsetValues() {
        // Given
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(createTestNodes());
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(document.getCharCount()); // Boundary value
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(createTestNodes());
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(createTestNodes());
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should handle boundary values
        verify(nodeRepository, times(3)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle nodes with minimum offset values")
    void shouldHandleNodesWithMinimumOffsetValues() {
        // Given
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(createTestNodes());
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(1); // Minimum valid range
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(createTestNodes());
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(createTestNodes());
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should handle minimum values
        verify(nodeRepository, times(3)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle nodes with all null anchors")
    void shouldHandleNodesWithAllNullAnchors() {
        // Given
        List<DocumentNode> nullAnchorNodes = createNullAnchorNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(nullAnchorNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");
    }

    @Test
    @DisplayName("Should handle nodes with empty anchors")
    void shouldHandleNodesWithEmptyAnchors() {
        // Given
        List<DocumentNode> emptyAnchorNodes = createEmptyAnchorNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(emptyAnchorNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");
    }

    @Test
    @DisplayName("Should handle document with empty text")
    void shouldHandleDocumentWithEmptyText() {
        // Given
        document.setNormalizedText("");
        document.setCharCount(0);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Document has no normalized text");
    }

    @Test
    @DisplayName("Should handle document with single character text")
    void shouldHandleDocumentWithSingleCharacterText() {
        // Given
        document.setNormalizedText("A");
        document.setCharCount(1);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(createTestNodes());
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(1);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(createTestNodes());
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(createTestNodes());
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
        doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Should handle single character
        verify(nodeRepository, times(3)).saveAll(anyList());
    }

    private String createTestDocumentText() {
        return "This is a test document for edge case testing. " +
               "It contains various scenarios to validate system robustness. " +
               "The content is designed to test boundary conditions and unusual inputs.";
    }

    private List<DocumentNode> createTestNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode root = new DocumentNode();
        root.setType(DocumentNode.NodeType.CHAPTER);
        root.setTitle("Test Root");
        root.setStartAnchor("Test Root");
        root.setEndAnchor("End Test Root");
        root.setDepth((short) 0);
        root.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(root);

        DocumentNode section = new DocumentNode();
        section.setType(DocumentNode.NodeType.SECTION);
        section.setTitle("Test Section");
        section.setStartAnchor("Test Section");
        section.setEndAnchor("End Test Section");
        section.setDepth((short) 1);
        section.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(section);

        DocumentNode subsection = new DocumentNode();
        subsection.setType(DocumentNode.NodeType.SUBSECTION);
        subsection.setTitle("Test Subsection");
        subsection.setStartAnchor("Test Subsection");
        subsection.setEndAnchor("End Test Subsection");
        subsection.setDepth((short) 2);
        subsection.setAiConfidence(BigDecimal.valueOf(0.85));
        nodes.add(subsection);

        return nodes;
    }

    private List<DocumentNode> createLowConfidenceNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode lowConf1 = new DocumentNode();
        lowConf1.setType(DocumentNode.NodeType.CHAPTER);
        lowConf1.setTitle("Low Confidence 1");
        lowConf1.setStartAnchor("Low Confidence 1");
        lowConf1.setEndAnchor("End Low Confidence 1");
        lowConf1.setDepth((short) 0);
        lowConf1.setAiConfidence(BigDecimal.ZERO);
        nodes.add(lowConf1);

        DocumentNode lowConf2 = new DocumentNode();
        lowConf2.setType(DocumentNode.NodeType.SECTION);
        lowConf2.setTitle("Low Confidence 2");
        lowConf2.setStartAnchor("Low Confidence 2");
        lowConf2.setEndAnchor("End Low Confidence 2");
        lowConf2.setDepth((short) 1);
        lowConf2.setAiConfidence(BigDecimal.valueOf(0.01));
        nodes.add(lowConf2);

        return nodes;
    }

    private List<DocumentNode> createHighConfidenceNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode highConf1 = new DocumentNode();
        highConf1.setType(DocumentNode.NodeType.CHAPTER);
        highConf1.setTitle("High Confidence 1");
        highConf1.setStartAnchor("High Confidence 1");
        highConf1.setEndAnchor("End High Confidence 1");
        highConf1.setDepth((short) 0);
        highConf1.setAiConfidence(BigDecimal.valueOf(0.999999));
        nodes.add(highConf1);

        DocumentNode highConf2 = new DocumentNode();
        highConf2.setType(DocumentNode.NodeType.SECTION);
        highConf2.setTitle("High Confidence 2");
        highConf2.setStartAnchor("High Confidence 2");
        highConf2.setEndAnchor("End High Confidence 2");
        highConf2.setDepth((short) 1);
        highConf2.setAiConfidence(BigDecimal.valueOf(1.0));
        nodes.add(highConf2);

        return nodes;
    }

    private List<DocumentNode> createLongTitleNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode longTitle = new DocumentNode();
        longTitle.setType(DocumentNode.NodeType.CHAPTER);
        longTitle.setTitle("This is an extremely long title that contains many words and should test the system's ability to handle very long titles without any issues. The title continues for quite a while to ensure we test the boundary conditions properly.");
        longTitle.setStartAnchor("Long Title");
        longTitle.setEndAnchor("End Long Title");
        longTitle.setDepth((short) 0);
        longTitle.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(longTitle);

        return nodes;
    }

    private List<DocumentNode> createEmptyTitleNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode emptyTitle = new DocumentNode();
        emptyTitle.setType(DocumentNode.NodeType.CHAPTER);
        emptyTitle.setTitle("");
        emptyTitle.setStartAnchor("Empty Title");
        emptyTitle.setEndAnchor("End Empty Title");
        emptyTitle.setDepth((short) 0);
        emptyTitle.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(emptyTitle);

        return nodes;
    }

    private List<DocumentNode> createWhitespaceTitleNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode whitespaceTitle = new DocumentNode();
        whitespaceTitle.setType(DocumentNode.NodeType.CHAPTER);
        whitespaceTitle.setTitle("   \t\n   ");
        whitespaceTitle.setStartAnchor("Whitespace Title");
        whitespaceTitle.setEndAnchor("End Whitespace Title");
        whitespaceTitle.setDepth((short) 0);
        whitespaceTitle.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(whitespaceTitle);

        return nodes;
    }

    private List<DocumentNode> createVeryDeepNestedNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        for (short depth = 0; depth < 10; depth++) {
            DocumentNode node = new DocumentNode();
            node.setType(DocumentNode.NodeType.SUBSECTION);
            node.setTitle("Level " + depth);
            node.setStartAnchor("Level " + depth);
            node.setEndAnchor("End Level " + depth);
            node.setDepth(depth);
            node.setAiConfidence(BigDecimal.valueOf(0.95 - (depth * 0.01)));
            nodes.add(node);
        }

        return nodes;
    }

    private List<DocumentNode> createMixedDepthOrderingNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        // Mixed order: depth 2, depth 0, depth 1
        DocumentNode depth2 = new DocumentNode();
        depth2.setType(DocumentNode.NodeType.SUBSECTION);
        depth2.setTitle("Depth 2");
        depth2.setStartAnchor("Depth 2");
        depth2.setEndAnchor("End Depth 2");
        depth2.setDepth((short) 2);
        depth2.setAiConfidence(BigDecimal.valueOf(0.85));
        nodes.add(depth2);

        DocumentNode depth0 = new DocumentNode();
        depth0.setType(DocumentNode.NodeType.CHAPTER);
        depth0.setTitle("Depth 0");
        depth0.setStartAnchor("Depth 0");
        depth0.setEndAnchor("End Depth 0");
        depth0.setDepth((short) 0);
        depth0.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(depth0);

        DocumentNode depth1 = new DocumentNode();
        depth1.setType(DocumentNode.NodeType.SECTION);
        depth1.setTitle("Depth 1");
        depth1.setStartAnchor("Depth 1");
        depth1.setEndAnchor("End Depth 1");
        depth1.setDepth((short) 1);
        depth1.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(depth1);

        return nodes;
    }

    private List<DocumentNode> createNullAnchorNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode nullAnchor = new DocumentNode();
        nullAnchor.setType(DocumentNode.NodeType.CHAPTER);
        nullAnchor.setTitle("Null Anchor");
        nullAnchor.setStartAnchor(null);
        nullAnchor.setEndAnchor(null);
        nullAnchor.setDepth((short) 0);
        nullAnchor.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(nullAnchor);

        return nodes;
    }

    private List<DocumentNode> createEmptyAnchorNodes() {
        List<DocumentNode> nodes = new ArrayList<>();

        DocumentNode emptyAnchor = new DocumentNode();
        emptyAnchor.setType(DocumentNode.NodeType.CHAPTER);
        emptyAnchor.setTitle("Empty Anchor");
        emptyAnchor.setStartAnchor("");
        emptyAnchor.setEndAnchor("");
        emptyAnchor.setDepth((short) 0);
        emptyAnchor.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(emptyAnchor);

        return nodes;
    }
}
