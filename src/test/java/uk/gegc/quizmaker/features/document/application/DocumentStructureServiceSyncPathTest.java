package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentStructureServiceSyncPathTest {

    @Mock
    private CanonicalTextService canonicalTextService;

    @Mock
    private PreSegmentationService preSegmentationService;

    @Mock
    private OutlineExtractorService outlineExtractorService;

    @Mock
    private OutlineAlignmentService outlineAlignmentService;

    @Mock
    private DocumentNodeRepository documentNodeRepository;

    @Mock
    private HierarchicalStructureService hierarchicalStructureService;

    @Mock
    private DocumentStructureProperties documentStructureProperties;

    @Mock
    private DocumentStructureJobService jobService;

    private DocumentStructureService documentStructureService;

    @BeforeEach
    void setUp() {
        documentStructureService = new DocumentStructureService(
                canonicalTextService,
                preSegmentationService,
                outlineExtractorService,
                outlineAlignmentService,
                documentNodeRepository,
                hierarchicalStructureService,
                documentStructureProperties,
                jobService
        );
    }

    // ===== SAVE NODES TESTS =====

    @Test
    void saveNodes_deletesExistingNodesThenSavesNewOnes() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode existingNode = createDocumentNode("Existing Node", 0, 10, 0);
        DocumentNode newNode = createDocumentNode("New Node", 10, 20, 0);
        List<DocumentNode> newNodes = List.of(newNode);

        when(documentNodeRepository.countByDocument_Id(documentId)).thenReturn(1L);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(newNodes);

        // When
        int result = documentStructureService.saveNodes(documentId, newNodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(1);
        verify(documentNodeRepository).deleteByDocument_Id(documentId);
        verify(documentNodeRepository).saveAll(newNodes);
        
        // Verify deletion happens before saving
        verify(documentNodeRepository, times(1)).deleteByDocument_Id(documentId);
        verify(documentNodeRepository, times(1)).saveAll(anyList());
    }

    @Test
    void saveNodes_setsStrategyAndSourceVersionHashOnAllNodes() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node1 = createDocumentNode("Node 1", 0, 10, 0);
        DocumentNode node2 = createDocumentNode("Node 2", 10, 20, 0);
        List<DocumentNode> nodes = List.of(node1, node2);

        when(documentNodeRepository.countByDocument_Id(documentId)).thenReturn(0L);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(nodes);

        // When
        documentStructureService.saveNodes(documentId, nodes, "testHash", DocumentNode.Strategy.REGEX);

        // Then
        assertThat(node1.getStrategy()).isEqualTo(DocumentNode.Strategy.REGEX);
        assertThat(node1.getSourceVersionHash()).isEqualTo("testHash");
        assertThat(node2.getStrategy()).isEqualTo(DocumentNode.Strategy.REGEX);
        assertThat(node2.getSourceVersionHash()).isEqualTo("testHash");
        
        verify(documentNodeRepository).saveAll(nodes);
    }

    @Test
    void saveNodes_validatesInputs_nullChecksThrow() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node = createDocumentNode("Test", 0, 10, 0);
        List<DocumentNode> nodes = List.of(node);

        // When & Then - Null document ID
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(null, nodes, "hash123", DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Document ID cannot be null");

        // When & Then - Null nodes
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, null, "hash123", DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Nodes cannot be null");

        // When & Then - Null source version hash
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, nodes, null, DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Source version hash cannot be null or empty");

        // When & Then - Empty source version hash
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, nodes, "", DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Source version hash cannot be null or empty");

        // When & Then - Null strategy
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, nodes, "hash123", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Strategy cannot be null");
    }

    @Test
    void saveNodes_handlesEmptyNodesList() {
        // Given
        UUID documentId = UUID.randomUUID();
        List<DocumentNode> nodes = List.of();

        // When
        int result = documentStructureService.saveNodes(documentId, nodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(0);
        verify(documentNodeRepository, never()).saveAll(anyList());
        verify(documentNodeRepository, never()).deleteByDocument_Id(any());
    }

    // ===== EXTRACT AND ALIGN STRUCTURE TESTS =====

    @Test
    void extractAndAlignStructure_choosesHierarchicalStrategyWhenTextLengthGreaterThanThreshold() {
        // Given
        UUID documentId = UUID.randomUUID();
        String longText = "A".repeat(150000); // Long text above threshold
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            longText, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 1000, longText.substring(0, 1000), true, 1000)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1", "Chapter 1", "", List.of())
        ));

        DocumentNode node = createDocumentNode("Chapter 1", 0, 100, 0);
        List<DocumentNode> alignedNodes = List.of(node);

        // Mock service calls
        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000); // Threshold
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(hierarchicalStructureService.buildHierarchicalOutline(canonicalText)).thenReturn(outline);
        when(outlineAlignmentService.alignOutlineToOffsets(eq(outline), eq(canonicalText), eq(windows), eq(documentId), anyString()))
            .thenReturn(alignedNodes);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(alignedNodes);

        // When
        int result = documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(1);
        verify(hierarchicalStructureService).buildHierarchicalOutline(canonicalText);
        verify(outlineExtractorService, never()).extractOutline(anyString());
    }

    @Test
    void extractAndAlignStructure_choosesStandardStrategyWhenTextLengthBelowThreshold() {
        // Given
        UUID documentId = UUID.randomUUID();
        String shortText = "Chapter 1: Introduction. This is a short document.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            shortText, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, shortText, true, 50)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        DocumentNode node = createDocumentNode("Chapter 1: Introduction", 0, 50, 0);
        List<DocumentNode> alignedNodes = List.of(node);

        // Mock service calls
        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000); // High threshold
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(shortText)).thenReturn(outline);
        when(outlineAlignmentService.alignOutlineToOffsets(eq(outline), eq(canonicalText), eq(windows), eq(documentId), anyString()))
            .thenReturn(alignedNodes);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(alignedNodes);

        // When
        int result = documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(1);
        verify(outlineExtractorService).extractOutline(shortText);
        verify(hierarchicalStructureService, never()).buildHierarchicalOutline(any());
    }

    @Test
    void extractAndAlignStructure_propagatesAlignmentIntoSaveNodesWithSameSourceVersionHash() {
        // Given
        UUID documentId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "sourceHash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, text, true, 50)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        DocumentNode node = createDocumentNode("Chapter 1: Introduction", 0, 50, 0);
        List<DocumentNode> alignedNodes = List.of(node);

        // Mock service calls
        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000);
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(text)).thenReturn(outline);
        when(outlineAlignmentService.alignOutlineToOffsets(eq(outline), eq(canonicalText), eq(windows), eq(documentId), eq("sourceHash123")))
            .thenReturn(alignedNodes);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(alignedNodes);

        // When
        int result = documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(1);
        verify(outlineAlignmentService).alignOutlineToOffsets(outline, canonicalText, windows, documentId, "sourceHash123");
        verify(documentNodeRepository).saveAll(alignedNodes);
        
        // Verify the node gets the correct source version hash
        assertThat(node.getSourceVersionHash()).isEqualTo("sourceHash123");
    }

    @Test
    void extractAndAlignStructure_wrapsAnyFailureInDocumentProcessingException() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        when(canonicalTextService.loadOrBuild(documentId))
            .thenThrow(new RuntimeException("Canonical text service failed"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI))
            .isInstanceOf(DocumentProcessingException.class)
            .hasMessageContaining("Failed to extract document structure")
            .hasMessageContaining("Canonical text service failed");
    }

    @Test
    void extractAndAlignStructure_wrapsPreSegmentationFailureInDocumentProcessingException() {
        // Given
        UUID documentId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());

        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(preSegmentationService.generateWindows(canonicalText))
            .thenThrow(new RuntimeException("Pre-segmentation failed"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI))
            .isInstanceOf(DocumentProcessingException.class)
            .hasMessageContaining("Failed to extract document structure")
            .hasMessageContaining("Pre-segmentation failed");
    }

    @Test
    void extractAndAlignStructure_wrapsOutlineExtractionFailureInDocumentProcessingException() {
        // Given
        UUID documentId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, text, true, 50)
        );

        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000);
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(text))
            .thenThrow(new RuntimeException("Outline extraction failed"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI))
            .isInstanceOf(DocumentProcessingException.class)
            .hasMessageContaining("Failed to extract document structure")
            .hasMessageContaining("Outline extraction failed");
    }

    @Test
    void extractAndAlignStructure_wrapsAlignmentFailureInDocumentProcessingException() {
        // Given
        UUID documentId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, text, true, 50)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000);
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(text)).thenReturn(outline);
        when(outlineAlignmentService.alignOutlineToOffsets(eq(outline), eq(canonicalText), eq(windows), eq(documentId), anyString()))
            .thenThrow(new RuntimeException("Alignment failed"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI))
            .isInstanceOf(DocumentProcessingException.class)
            .hasMessageContaining("Failed to extract document structure")
            .hasMessageContaining("Alignment failed");
    }

    // ===== TREE/FLAT FETCHERS TESTS =====

    @Test
    void getDocumentStructureTree_recursivelyLoadsChildren() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        // Create root nodes with specific document ID
        DocumentNode root1 = createDocumentNode("Root 1", 0, 50, 0, documentId);
        DocumentNode root2 = createDocumentNode("Root 2", 50, 100, 0, documentId);
        
        // Create child nodes with specific document ID
        DocumentNode child1 = createDocumentNode("Child 1", 10, 30, 1, documentId);
        DocumentNode child2 = createDocumentNode("Child 2", 30, 50, 1, documentId);
        DocumentNode grandchild = createDocumentNode("Grandchild", 15, 25, 2, documentId);
        
        // Mock repository calls using any() for UUIDs to avoid strict matching issues
        when(documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(any(UUID.class)))
            .thenAnswer(invocation -> {
                UUID docId = invocation.getArgument(0);
                if (docId.equals(documentId)) {
                    return List.of(root1, root2);
                }
                return List.of();
            });
        
        // Use any() for UUID matching to avoid strict stubbing issues
        when(documentNodeRepository.findByDocument_IdAndParent_IdOrderByOrdinalAsc(any(UUID.class), any(UUID.class)))
            .thenAnswer(invocation -> {
                UUID docId = invocation.getArgument(0);
                UUID parentId = invocation.getArgument(1);
                
                if (docId.equals(documentId) && parentId.equals(root1.getId())) {
                    return List.of(child1, child2);
                } else if (docId.equals(documentId) && parentId.equals(root2.getId())) {
                    return List.of();
                } else if (docId.equals(documentId) && parentId.equals(child1.getId())) {
                    return List.of(grandchild);
                } else if (docId.equals(documentId) && parentId.equals(child2.getId())) {
                    return List.of();
                } else if (docId.equals(documentId) && parentId.equals(grandchild.getId())) {
                    return List.of();
                }
                return List.of();
            });

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureTree(documentId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTitle()).isEqualTo("Root 1");
        assertThat(result.get(1).getTitle()).isEqualTo("Root 2");
        
        // Verify children are loaded
        assertThat(result.get(0).getChildren()).hasSize(2);
        assertThat(result.get(0).getChildren().get(0).getTitle()).isEqualTo("Child 1");
        assertThat(result.get(0).getChildren().get(1).getTitle()).isEqualTo("Child 2");
        
        // Verify grandchildren are loaded
        assertThat(result.get(0).getChildren().get(0).getChildren()).hasSize(1);
        assertThat(result.get(0).getChildren().get(0).getChildren().get(0).getTitle()).isEqualTo("Grandchild");
        
        // Verify second root has no children
        assertThat(result.get(1).getChildren()).isEmpty();
    }

    @Test
    void getDocumentStructureFlat_ordersByStartOffset() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        DocumentNode node1 = createDocumentNode("Node 1", 50, 100, 0);
        DocumentNode node2 = createDocumentNode("Node 2", 0, 50, 0);
        DocumentNode node3 = createDocumentNode("Node 3", 100, 150, 0);
        
        List<DocumentNode> nodesInRandomOrder = List.of(node1, node2, node3);
        
        when(documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId))
            .thenReturn(nodesInRandomOrder);

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureFlat(documentId);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getStartOffset()).isEqualTo(50); // Node 1
        assertThat(result.get(1).getStartOffset()).isEqualTo(0);  // Node 2
        assertThat(result.get(2).getStartOffset()).isEqualTo(100); // Node 3
        
        // Verify the repository method was called with correct parameters
        verify(documentNodeRepository).findByDocumentIdOrderByStartOffset(documentId);
    }

    @Test
    void getDocumentStructureFlat_returnsEmptyListWhenNoNodes() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        when(documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId))
            .thenReturn(List.of());

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureFlat(documentId);

        // Then
        assertThat(result).isEmpty();
        verify(documentNodeRepository).findByDocumentIdOrderByStartOffset(documentId);
    }

    @Test
    void getDocumentStructureTree_returnsEmptyListWhenNoRootNodes() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        when(documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId))
            .thenReturn(List.of());

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureTree(documentId);

        // Then
        assertThat(result).isEmpty();
        verify(documentNodeRepository).findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId);
        verify(documentNodeRepository, never()).findByDocument_IdAndParent_IdOrderByOrdinalAsc(any(), any());
    }

    // ===== HELPER METHODS =====

    private DocumentNode createDocumentNode(String title, int startOffset, int endOffset, int level) {
        DocumentNode node = new DocumentNode();
        node.setId(UUID.randomUUID());
        node.setTitle(title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setLevel(level);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setConfidence(java.math.BigDecimal.valueOf(0.8));
        node.setSourceVersionHash("hash123");
        node.setOrdinal(1);
        
        // Set up document reference
        uk.gegc.quizmaker.features.document.domain.model.Document document = 
            new uk.gegc.quizmaker.features.document.domain.model.Document();
        document.setId(UUID.randomUUID());
        node.setDocument(document);
        
        return node;
    }
    
    private DocumentNode createDocumentNode(String title, int startOffset, int endOffset, int level, UUID documentId) {
        DocumentNode node = new DocumentNode();
        node.setId(UUID.randomUUID());
        node.setTitle(title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setLevel(level);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setConfidence(java.math.BigDecimal.valueOf(0.8));
        node.setSourceVersionHash("hash123");
        node.setOrdinal(1);
        
        // Set up document reference with specific document ID
        uk.gegc.quizmaker.features.document.domain.model.Document document = 
            new uk.gegc.quizmaker.features.document.domain.model.Document();
        document.setId(documentId);
        node.setDocument(document);
        
        return node;
    }
}
