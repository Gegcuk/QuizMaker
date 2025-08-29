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

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LayerByLayerResilienceTest {

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

    @Mock
    private ChunkedStructureService chunkedStructureService;

    @InjectMocks
    private StructureService service;

    private UUID documentId;
    private NormalizedDocument document;
    private List<DocumentNode> multiLevelNodes;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("resilience-test.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setNormalizedText(createTestDocumentText());
        document.setCharCount(createTestDocumentText().length());

        multiLevelNodes = createMultiLevelStructure();
    }

    @Test
    @DisplayName("Should save layers incrementally and handle failures gracefully")
    void shouldSaveLayersIncrementallyAndHandleFailuresGracefully() {
        // Given - Setup mocks to simulate layer-by-layer processing
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.needsChunking(anyString())).thenReturn(false);
        when(llmClient.generateStructure(any(), any())).thenReturn(multiLevelNodes);
        
        // Mock anchorOffsetCalculator to work normally for depth 0 and 1, but fail for depth 2
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                Short depth = nodes.get(0).getDepth();
                
                if (depth == 0) {
                    // Depth 0 - process normally
                    nodes.forEach(node -> {
                        node.setStartOffset(0);
                        node.setEndOffset(100);
                    });
                    return nodes;
                } else if (depth == 1) {
                    // Depth 1 - process normally
                    nodes.forEach(node -> {
                        node.setStartOffset(50);
                        node.setEndOffset(150);
                    });
                    return nodes;
                } else {
                    // Depth 2 - simulate failure
                    throw new RuntimeException("Anchor calculation failed for depth 2");
                }
            });

        // Mock saveAll to track what gets saved
        List<List<DocumentNode>> savedLayers = new ArrayList<>();
        when(nodeRepository.saveAll(anyList()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                savedLayers.add(new ArrayList<>(nodes));
                return nodes;
            });

        // When & Then - Should throw exception but previous layers should be saved
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");

        // Verify that saveAll was called for successful layers only
        verify(nodeRepository, times(2)).saveAll(anyList()); // Only depth 0 and 1 should be saved

        // Verify that exactly 2 layers were saved (depth 0 and 1)
        assertThat(savedLayers).hasSize(2);
        
        // Verify depth 0 layer was saved
        assertThat(savedLayers.get(0)).hasSize(1);
        assertThat(savedLayers.get(0).get(0).getDepth()).isEqualTo((short) 0);
        assertThat(savedLayers.get(0).get(0).getTitle()).isEqualTo("Root Chapter");
        
        // Verify depth 1 layer was saved
        assertThat(savedLayers.get(1)).hasSize(2);
        assertThat(savedLayers.get(1).get(0).getDepth()).isEqualTo((short) 1);
        assertThat(savedLayers.get(1).get(1).getDepth()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("Should handle saveAll failure for specific layer")
    void shouldHandleSaveAllFailureForSpecificLayer() {
        // Given - Setup mocks
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.needsChunking(anyString())).thenReturn(false);
        when(llmClient.generateStructure(any(), any())).thenReturn(multiLevelNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });

        // Mock saveAll to fail on depth 1
        when(nodeRepository.saveAll(anyList()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                Short depth = nodes.get(0).getDepth();
                
                if (depth == 0) {
                    return nodes; // Success for depth 0
                } else if (depth == 1) {
                    throw new RuntimeException("Database save failed for depth 1");
                } else {
                    return nodes; // Success for other depths
                }
            });

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");

        // Verify that saveAll was called twice (for depth 0 and depth 1)
        verify(nodeRepository, times(2)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle parent relationship assignment failure")
    void shouldHandleParentRelationshipAssignmentFailure() {
        // Given - Setup mocks
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.needsChunking(anyString())).thenReturn(false);
        when(llmClient.generateStructure(any(), any())).thenReturn(multiLevelNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(multiLevelNodes);

        // Mock parent relationship query to fail for depth 1
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), eq((short) 1)))
            .thenThrow(new RuntimeException("Database query failed"));

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");

        // Verify that saveAll was called only once (for depth 0)
        verify(nodeRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should process all layers successfully when no failures occur")
    void shouldProcessAllLayersSuccessfullyWhenNoFailuresOccur() {
        // Given - Setup mocks for successful processing
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.needsChunking(anyString())).thenReturn(false);
        when(llmClient.generateStructure(any(), any())).thenReturn(multiLevelNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(multiLevelNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());

        // Mock hierarchy builder
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Verify all layers were processed
        verify(nodeRepository, times(3)).saveAll(anyList()); // All 3 depth levels
        verify(nodeRepository, times(2)).findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort());
    }

    @Test
    @DisplayName("Should handle chunked processing failure gracefully")
    void shouldHandleChunkedProcessingFailureGracefully() {
        // Given - Setup mocks for large document that needs chunking
        String largeText = "A".repeat(300_000); // 300KB text - needs chunking
        document.setNormalizedText(largeText);
        document.setCharCount(largeText.length());
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.needsChunking(largeText)).thenReturn(true);
        when(chunkedStructureService.processLargeDocument(eq(largeText), any(), eq(documentId.toString())))
            .thenThrow(new RuntimeException("Chunked processing failed"));

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");

        // Verify that chunked processing was attempted
        verify(chunkedStructureService).processLargeDocument(eq(largeText), any(), eq(documentId.toString()));
        verify(llmClient, never()).generateStructure(any(), any());
    }

    @Test
    @DisplayName("Should handle chunked processing success with layer-by-layer resilience")
    void shouldHandleChunkedProcessingSuccessWithLayerByLayerResilience() {
        // Given - Setup mocks for large document that needs chunking
        String largeText = "A".repeat(300_000); // 300KB text - needs chunking
        document.setNormalizedText(largeText);
        document.setCharCount(largeText.length());
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.needsChunking(largeText)).thenReturn(true);
        when(chunkedStructureService.processLargeDocument(eq(largeText), any(), eq(documentId.toString())))
            .thenReturn(multiLevelNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenAnswer(invocation -> {
                List<DocumentNode> nodes = invocation.getArgument(0);
                nodes.forEach(node -> {
                    node.setStartOffset(0);
                    node.setEndOffset(100);
                });
                return nodes;
            });
        when(nodeRepository.saveAll(anyList())).thenReturn(multiLevelNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());

        // When
        service.buildStructure(documentId);

        // Then - Verify chunked processing was used and layers were saved
        verify(chunkedStructureService).processLargeDocument(eq(largeText), any(), eq(documentId.toString()));
        verify(nodeRepository, times(3)).saveAll(anyList()); // All 3 depth levels
        verify(llmClient, never()).generateStructure(any(), any());
    }

    private String createTestDocumentText() {
        return "This is a test document for resilience testing. " +
               "It contains multiple sections and subsections. " +
               "The content is structured in a hierarchical manner. " +
               "Each level has its own specific content and structure. " +
               "This allows us to test layer-by-layer processing effectively.";
    }

    private List<DocumentNode> createMultiLevelStructure() {
        List<DocumentNode> nodes = new ArrayList<>();

        // Depth 0 - Root level
        DocumentNode root = new DocumentNode();
        root.setType(DocumentNode.NodeType.CHAPTER);
        root.setTitle("Root Chapter");
        root.setStartAnchor("Root Chapter");
        root.setEndAnchor("End Root Chapter");
        root.setDepth((short) 0);
        root.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(root);

        // Depth 1 - First level children
        DocumentNode section1 = new DocumentNode();
        section1.setType(DocumentNode.NodeType.SECTION);
        section1.setTitle("Section 1");
        section1.setStartAnchor("Section 1");
        section1.setEndAnchor("End Section 1");
        section1.setDepth((short) 1);
        section1.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(section1);

        DocumentNode section2 = new DocumentNode();
        section2.setType(DocumentNode.NodeType.SECTION);
        section2.setTitle("Section 2");
        section2.setStartAnchor("Section 2");
        section2.setEndAnchor("End Section 2");
        section2.setDepth((short) 1);
        section2.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(section2);

        // Depth 2 - Second level children
        DocumentNode subsection1 = new DocumentNode();
        subsection1.setType(DocumentNode.NodeType.SUBSECTION);
        subsection1.setTitle("Subsection 1.1");
        subsection1.setStartAnchor("Subsection 1.1");
        subsection1.setEndAnchor("End Subsection 1.1");
        subsection1.setDepth((short) 2);
        subsection1.setAiConfidence(BigDecimal.valueOf(0.85));
        nodes.add(subsection1);

        DocumentNode subsection2 = new DocumentNode();
        subsection2.setType(DocumentNode.NodeType.SUBSECTION);
        subsection2.setTitle("Subsection 1.2");
        subsection2.setStartAnchor("Subsection 1.2");
        subsection2.setEndAnchor("End Subsection 1.2");
        subsection2.setDepth((short) 2);
        subsection2.setAiConfidence(BigDecimal.valueOf(0.85));
        nodes.add(subsection2);

        return nodes;
    }
}
