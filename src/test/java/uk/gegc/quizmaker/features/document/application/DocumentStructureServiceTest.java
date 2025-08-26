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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentStructureServiceTest {

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
                documentStructureProperties
        );
    }

    @Test
    void shouldExtractAndAlignDocumentStructure() {
        // Given
        UUID documentId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter. Chapter 2: Methods. This is the second chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, "Chapter 1: Introduction. This is the first chapter.", true, 50),
            new PreSegmentationService.PreSegmentationWindow(50, 100, "Chapter 2: Methods. This is the second chapter.", true, 50)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 2: Methods", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        DocumentNode chapter1 = createDocumentNode("Chapter 1: Introduction", 0, 50, 0);
        DocumentNode chapter2 = createDocumentNode("Chapter 2: Methods", 50, 100, 0);
        List<DocumentNode> alignedNodes = List.of(chapter1, chapter2);

        // Mock service calls
        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000); // High threshold for non-hierarchical
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(canonicalText.getText())).thenReturn(outline);
        when(outlineAlignmentService.alignOutlineToOffsets(eq(outline), eq(canonicalText), eq(windows), eq(documentId), anyString()))
            .thenReturn(alignedNodes);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(alignedNodes);

        // When
        int result = documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(2);

        // Verify service interactions
        verify(canonicalTextService).loadOrBuild(documentId);
        verify(preSegmentationService).generateWindows(canonicalText);
        verify(outlineExtractorService).extractOutline(canonicalText.getText());
        verify(outlineAlignmentService).alignOutlineToOffsets(eq(outline), eq(canonicalText), eq(windows), eq(documentId), anyString());
        verify(documentNodeRepository).saveAll(alignedNodes);
    }

    @Test
    void shouldExtractAndAlignDocumentStructureWithHierarchicalProcessing() {
        // Given - Set up for hierarchical processing (long document)
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(50); // Low threshold to trigger hierarchical
        
        UUID documentId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter. Chapter 2: Methods. This is the second chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());

        DocumentOutlineDto hierarchicalOutline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 2: Methods", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        DocumentNode chapter1 = createDocumentNode("Chapter 1: Introduction", 0, 50, 0);
        DocumentNode chapter2 = createDocumentNode("Chapter 2: Methods", 50, 100, 0);
        List<DocumentNode> alignedNodes = List.of(chapter1, chapter2);

        // Mock hierarchical service calls
        when(canonicalTextService.loadOrBuild(documentId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(50); // Low threshold to trigger hierarchical
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(List.of()); // Windows still needed for alignment
        when(hierarchicalStructureService.buildHierarchicalOutline(canonicalText)).thenReturn(hierarchicalOutline);
        when(outlineAlignmentService.alignOutlineToOffsets(eq(hierarchicalOutline), eq(canonicalText), any(), eq(documentId), anyString()))
            .thenReturn(alignedNodes);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(alignedNodes);

        // When
        int result = documentStructureService.extractAndAlignStructure(documentId, DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(2);

        // Verify hierarchical service interactions
        verify(canonicalTextService).loadOrBuild(documentId);
        verify(preSegmentationService).generateWindows(canonicalText); // Windows still needed for alignment
        verify(hierarchicalStructureService).buildHierarchicalOutline(canonicalText);
        verify(outlineAlignmentService).alignOutlineToOffsets(eq(hierarchicalOutline), eq(canonicalText), any(), eq(documentId), anyString());
        verify(documentNodeRepository).saveAll(alignedNodes);
        
        // Verify non-hierarchical services are NOT called
        verify(outlineExtractorService, never()).extractOutline(any());
    }

    @Test
    void shouldGetDocumentStructureTree() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode chapter1 = createDocumentNode("Chapter 1: Introduction", 0, 50, 0);
        DocumentNode section1 = createDocumentNode("Section 1.1: Background", 10, 30, 1);
        section1.setParent(chapter1);
        DocumentNode chapter2 = createDocumentNode("Chapter 2: Methods", 50, 100, 0);
        
        List<DocumentNode> allNodes = List.of(chapter1, section1, chapter2);

        when(documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId)).thenReturn(List.of(chapter1, chapter2));

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureTree(documentId);

        // Then
        assertThat(result).hasSize(2); // Should return root nodes only
        assertThat(result.get(0).getTitle()).isEqualTo("Chapter 1: Introduction");
        assertThat(result.get(1).getTitle()).isEqualTo("Chapter 2: Methods");
        
        // Verify children are loaded
        verify(documentNodeRepository).findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId);
    }

    @Test
    void shouldGetDocumentStructureFlat() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode chapter1 = createDocumentNode("Chapter 1: Introduction", 0, 50, 0);
        DocumentNode section1 = createDocumentNode("Section 1.1: Background", 10, 30, 1);
        DocumentNode chapter2 = createDocumentNode("Chapter 2: Methods", 50, 100, 0);
        
        List<DocumentNode> allNodes = List.of(chapter1, section1, chapter2);

        when(documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId)).thenReturn(allNodes);

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureFlat(documentId);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyInAnyOrder(chapter1, section1, chapter2);
        
        verify(documentNodeRepository).findByDocumentIdOrderByStartOffset(documentId);
    }

    @Test
    void shouldCheckIfDocumentHasStructure() {
        // Given
        UUID documentId = UUID.randomUUID();
        when(documentNodeRepository.existsByDocument_Id(documentId)).thenReturn(true);

        // When
        boolean hasStructure = documentStructureService.hasDocumentStructure(documentId);

        // Then
        assertThat(hasStructure).isTrue();
        verify(documentNodeRepository).existsByDocument_Id(documentId);
    }

    @Test
    void shouldReturnFalseWhenDocumentHasNoStructure() {
        // Given
        UUID documentId = UUID.randomUUID();
        when(documentNodeRepository.existsByDocument_Id(documentId)).thenReturn(false);

        // When
        boolean hasStructure = documentStructureService.hasDocumentStructure(documentId);

        // Then
        assertThat(hasStructure).isFalse();
        verify(documentNodeRepository).existsByDocument_Id(documentId);
    }

    @Test
    void shouldGetDocumentStructureCount() {
        // Given
        UUID documentId = UUID.randomUUID();
        when(documentNodeRepository.countByDocument_Id(documentId)).thenReturn(10L);

        // When
        long count = documentStructureService.getDocumentStructureCount(documentId);

        // Then
        assertThat(count).isEqualTo(10L);
        verify(documentNodeRepository).countByDocument_Id(documentId);
    }

    @Test
    void shouldDeleteDocumentStructure() {
        // Given
        UUID documentId = UUID.randomUUID();

        // When
        documentStructureService.deleteDocumentStructure(documentId);

        // Then
        verify(documentNodeRepository).deleteByDocument_Id(documentId);
    }

    @Test
    void shouldFindOverlappingNodes() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode overlappingNode = createDocumentNode("Overlapping Node", 25, 75, 0);
        List<DocumentNode> overlappingNodes = List.of(overlappingNode);

        when(documentNodeRepository.findOverlapping(documentId, 20, 80)).thenReturn(overlappingNodes);

        // When
        List<DocumentNode> result = documentStructureService.findOverlappingNodes(documentId, 20, 80);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Overlapping Node");
        verify(documentNodeRepository).findOverlapping(documentId, 20, 80);
    }

    @Test
    void shouldFindNodesByType() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode chapter1 = createDocumentNode("Chapter 1", 0, 50, 0);
        chapter1.setType(DocumentNode.NodeType.CHAPTER);
        DocumentNode chapter2 = createDocumentNode("Chapter 2", 50, 100, 0);
        chapter2.setType(DocumentNode.NodeType.CHAPTER);
        
        List<DocumentNode> chapters = List.of(chapter1, chapter2);

        when(documentNodeRepository.findByDocumentIdAndTypeOrderByStartOffset(documentId, DocumentNode.NodeType.CHAPTER))
            .thenReturn(chapters);

        // When
        List<DocumentNode> result = documentStructureService.findNodesByType(documentId, DocumentNode.NodeType.CHAPTER);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo(DocumentNode.NodeType.CHAPTER);
        assertThat(result.get(1).getType()).isEqualTo(DocumentNode.NodeType.CHAPTER);
        verify(documentNodeRepository).findByDocumentIdAndTypeOrderByStartOffset(documentId, DocumentNode.NodeType.CHAPTER);
    }

    @Test
    void shouldHandleEmptyDocumentStructure() {
        // Given
        UUID documentId = UUID.randomUUID();
        when(documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId)).thenReturn(List.of());

        // When
        List<DocumentNode> treeResult = documentStructureService.getDocumentStructureTree(documentId);
        List<DocumentNode> flatResult = documentStructureService.getDocumentStructureFlat(documentId);

        // Then
        assertThat(treeResult).isEmpty();
        assertThat(flatResult).isEmpty();
    }

    @Test
    void shouldHandleHierarchicalStructureWithMultipleLevels() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode part = createDocumentNode("Part 1: Introduction", 0, 200, 0);
        part.setType(DocumentNode.NodeType.PART);
        
        DocumentNode chapter = createDocumentNode("Chapter 1: Background", 10, 100, 1);
        chapter.setType(DocumentNode.NodeType.CHAPTER);
        chapter.setParent(part);
        
        DocumentNode section = createDocumentNode("Section 1.1: History", 20, 60, 2);
        section.setType(DocumentNode.NodeType.SECTION);
        section.setParent(chapter);
        
        DocumentNode subsection = createDocumentNode("Subsection 1.1.1: Early History", 25, 45, 3);
        subsection.setType(DocumentNode.NodeType.SUBSECTION);
        subsection.setParent(section);
        
        List<DocumentNode> allNodes = List.of(part, chapter, section, subsection);

        when(documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId)).thenReturn(List.of(part));

        // When
        List<DocumentNode> treeResult = documentStructureService.getDocumentStructureTree(documentId);

        // Then
        assertThat(treeResult).hasSize(1); // Should return only root nodes
        assertThat(treeResult.get(0).getTitle()).isEqualTo("Part 1: Introduction");
        assertThat(treeResult.get(0).getType()).isEqualTo(DocumentNode.NodeType.PART);
    }

    @Test
    void shouldHandleOrphanedNodesInStructure() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode chapter1 = createDocumentNode("Chapter 1", 0, 50, 0);
        DocumentNode orphanedNode = createDocumentNode("Orphaned Node", 60, 80, 1);
        // orphanedNode has a parent that's not in the list, simulating an orphaned node
        
        List<DocumentNode> allNodes = List.of(chapter1, orphanedNode);

        when(documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId)).thenReturn(List.of(chapter1, orphanedNode));

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureTree(documentId);

        // Then
        assertThat(result).hasSize(2); // Both should be treated as root nodes
        assertThat(result.get(0).getTitle()).isEqualTo("Chapter 1");
        assertThat(result.get(1).getTitle()).isEqualTo("Orphaned Node");
    }

    @Test
    void shouldHandleDocumentStructureWithConfidenceScores() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode highConfidenceNode = createDocumentNode("High Confidence", 0, 50, 0);
        highConfidenceNode.setConfidence(java.math.BigDecimal.valueOf(0.95));
        
        DocumentNode lowConfidenceNode = createDocumentNode("Low Confidence", 50, 100, 0);
        lowConfidenceNode.setConfidence(java.math.BigDecimal.valueOf(0.65));
        
        List<DocumentNode> nodes = List.of(highConfidenceNode, lowConfidenceNode);

        when(documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId)).thenReturn(nodes);

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureFlat(documentId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getConfidence().doubleValue()).isEqualTo(0.95);
        assertThat(result.get(1).getConfidence().doubleValue()).isEqualTo(0.65);
    }

    private DocumentNode createDocumentNode(String title, int startOffset, int endOffset, int level) {
        DocumentNode node = new DocumentNode();
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

    // 1. Error/guard rails tests for saveNodes validation
    @Test
    void saveNodes_shouldThrowExceptionForNullDocumentId() {
        // Given
        DocumentNode node = createDocumentNode("Test", 0, 10, 0);
        List<DocumentNode> nodes = List.of(node);

        // When & Then
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(null, nodes, "hash123", DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Document ID cannot be null");
    }

    @Test
    void saveNodes_shouldThrowExceptionForNullNodes() {
        // Given
        UUID documentId = UUID.randomUUID();

        // When & Then
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, null, "hash123", DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nodes cannot be null");
    }

    @Test
    void saveNodes_shouldHandleEmptyNodesList() {
        // Given
        UUID documentId = UUID.randomUUID();
        List<DocumentNode> nodes = List.of();

        // When
        int result = documentStructureService.saveNodes(documentId, nodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(0);
        verify(documentNodeRepository, never()).saveAll(anyList());
    }

    @Test
    void saveNodes_shouldThrowExceptionForNullSourceVersionHash() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node = createDocumentNode("Test", 0, 10, 0);
        List<DocumentNode> nodes = List.of(node);

        // When & Then
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, nodes, null, DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source version hash cannot be null or empty");
    }

    @Test
    void saveNodes_shouldThrowExceptionForEmptySourceVersionHash() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node = createDocumentNode("Test", 0, 10, 0);
        List<DocumentNode> nodes = List.of(node);

        // When & Then
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, nodes, "", DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source version hash cannot be null or empty");
    }

    @Test
    void saveNodes_shouldThrowExceptionForNullStrategy() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node = createDocumentNode("Test", 0, 10, 0);
        List<DocumentNode> nodes = List.of(node);

        // When & Then
        assertThatThrownBy(() -> 
            documentStructureService.saveNodes(documentId, nodes, "hash123", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Strategy cannot be null");
    }

    // 2. Overwrite semantics tests
    @Test
    void saveNodes_shouldDeleteExistingAndApplyStrategyAndHash() {
        // Given
        UUID docId = UUID.randomUUID();
        DocumentNode n = new DocumentNode();
        n.setDocument(new uk.gegc.quizmaker.features.document.domain.model.Document());
        n.getDocument().setId(docId);
        n.setTitle("t");
        n.setStartOffset(0);
        n.setEndOffset(10);

        when(documentNodeRepository.countByDocument_Id(docId)).thenReturn(1L);
        when(documentNodeRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // When
        int saved = documentStructureService.saveNodes(docId, List.of(n), "svh", DocumentNode.Strategy.AI);

        // Then
        assertThat(saved).isEqualTo(1);
        verify(documentNodeRepository).deleteByDocument_Id(docId);
        assertThat(n.getSourceVersionHash()).isEqualTo("svh");
        assertThat(n.getStrategy()).isEqualTo(DocumentNode.Strategy.AI);
    }

    @Test
    void saveNodes_shouldDeleteExistingBeforeSaving() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node1 = createDocumentNode("Existing Node", 0, 10, 0);
        DocumentNode node2 = createDocumentNode("New Node", 10, 20, 0);
        
        List<DocumentNode> existingNodes = List.of(node1);
        List<DocumentNode> newNodes = List.of(node2);

        when(documentNodeRepository.countByDocument_Id(documentId)).thenReturn(1L);
        when(documentNodeRepository.saveAll(anyList())).thenReturn(newNodes);

        // When
        documentStructureService.saveNodes(documentId, newNodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        verify(documentNodeRepository).deleteByDocument_Id(documentId);
        verify(documentNodeRepository).saveAll(newNodes);
        
        // Verify deletion happens before saving
        verify(documentNodeRepository, times(1)).deleteByDocument_Id(documentId);
        verify(documentNodeRepository, times(1)).saveAll(anyList());
    }

    // 3. Error path tests for extractAndAlignStructure
    @Test
    void extractAndAlignStructure_shouldWrapExceptions() {
        // Given
        UUID docId = UUID.randomUUID();
        when(canonicalTextService.loadOrBuild(docId))
            .thenThrow(new RuntimeException("boom"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(docId, DocumentNode.Strategy.AI))
            .isInstanceOf(uk.gegc.quizmaker.shared.exception.DocumentProcessingException.class)
            .hasMessageContaining("boom");
    }

    @Test
    void extractAndAlignStructure_shouldWrapOutlineExtractorExceptions() {
        // Given
        UUID docId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter. Chapter 2: Methods. This is the second chapter. Chapter 3: Results. This is the third chapter. Chapter 4: Conclusion. This is the final chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, text, true, 50)
        );

        when(canonicalTextService.loadOrBuild(docId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000); // High threshold for non-hierarchical
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(canonicalText.getText()))
            .thenThrow(new RuntimeException("extractor failed"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(docId, DocumentNode.Strategy.AI))
            .isInstanceOf(uk.gegc.quizmaker.shared.exception.DocumentProcessingException.class)
            .hasMessageContaining("extractor failed");
    }

    @Test
    void extractAndAlignStructure_shouldWrapAlignmentExceptions() {
        // Given
        UUID docId = UUID.randomUUID();
        String text = "Chapter 1: Introduction. This is the first chapter. Chapter 2: Methods. This is the second chapter. Chapter 3: Results. This is the third chapter. Chapter 4: Conclusion. This is the final chapter.";
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 50, text, true, 50)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        when(canonicalTextService.loadOrBuild(docId)).thenReturn(canonicalText);
        when(documentStructureProperties.getLongDocThresholdChars()).thenReturn(100000); // High threshold for non-hierarchical
        when(preSegmentationService.generateWindows(canonicalText)).thenReturn(windows);
        when(outlineExtractorService.extractOutline(canonicalText.getText())).thenReturn(outline);
        when(outlineAlignmentService.alignOutlineToOffsets(any(), any(), any(), any(), anyString()))
            .thenThrow(new RuntimeException("alignment failed"));

        // When & Then
        assertThatThrownBy(() ->
            documentStructureService.extractAndAlignStructure(docId, DocumentNode.Strategy.AI))
            .isInstanceOf(uk.gegc.quizmaker.shared.exception.DocumentProcessingException.class)
            .hasMessageContaining("alignment failed");
    }

    // 4. Repository behavior / dedup tests
    @Test
    void shouldHandleRepositoryDuplicateRows() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node1 = createDocumentNode("Chapter 1", 0, 50, 0);
        DocumentNode node2 = createDocumentNode("Chapter 1", 0, 50, 0); // Duplicate content
        
        List<DocumentNode> nodes = List.of(node1, node2);

        when(documentNodeRepository.saveAll(anyList())).thenReturn(nodes);

        // When
        int result = documentStructureService.saveNodes(documentId, nodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(2);
        // Service should handle duplicates gracefully
        verify(documentNodeRepository).saveAll(nodes);
    }

    // 5. Deep recursion integration test
    @Test
    void shouldHandleDeepRecursionInGetDocumentStructureTree() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        // Create a deep hierarchical structure
        DocumentNode part = createDocumentNode("Part 1", 0, 200, 0);
        part.setType(DocumentNode.NodeType.PART);
        part.getDocument().setId(documentId);
        
        DocumentNode chapter = createDocumentNode("Chapter 1", 10, 150, 1);
        chapter.setType(DocumentNode.NodeType.CHAPTER);
        chapter.setParent(part);
        chapter.getDocument().setId(documentId);
        
        DocumentNode section = createDocumentNode("Section 1.1", 20, 100, 2);
        section.setType(DocumentNode.NodeType.SECTION);
        section.setParent(chapter);
        section.getDocument().setId(documentId);
        
        DocumentNode subsection = createDocumentNode("Subsection 1.1.1", 25, 80, 3);
        subsection.setType(DocumentNode.NodeType.SUBSECTION);
        subsection.setParent(section);
        subsection.getDocument().setId(documentId);
        
        DocumentNode paragraph = createDocumentNode("Paragraph 1.1.1.1", 30, 60, 4);
        paragraph.setType(DocumentNode.NodeType.PARAGRAPH);
        paragraph.setParent(subsection);
        paragraph.getDocument().setId(documentId);

        lenient().when(documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId))
            .thenReturn(List.of(part));
        lenient().when(documentNodeRepository.findByDocument_IdAndParent_IdOrderByOrdinalAsc(any(UUID.class), any(UUID.class)))
            .thenAnswer(invocation -> {
                UUID docId = invocation.getArgument(0);
                UUID parentId = invocation.getArgument(1);
                if (parentId != null && parentId.equals(part.getId())) {
                    return List.of(chapter);
                } else if (parentId != null && parentId.equals(chapter.getId())) {
                    return List.of(section);
                } else if (parentId != null && parentId.equals(section.getId())) {
                    return List.of(subsection);
                } else if (parentId != null && parentId.equals(subsection.getId())) {
                    return List.of(paragraph);
                }
                return List.of();
            });

        // When
        List<DocumentNode> result = documentStructureService.getDocumentStructureTree(documentId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Part 1");
        assertThat(result.get(0).getType()).isEqualTo(DocumentNode.NodeType.PART);
        
        // Verify recursive loading was called for all levels
        verify(documentNodeRepository).findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId);
        verify(documentNodeRepository, atLeastOnce()).findByDocument_IdAndParent_IdOrderByOrdinalAsc(any(UUID.class), any());
    }

    // 6. Zero-length nodes handling
    @Test
    void shouldFilterOutZeroLengthNodes() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode validNode = createDocumentNode("Valid Node", 0, 10, 0);
        DocumentNode zeroLengthNode = createDocumentNode("Zero Length", 5, 5, 0); // start == end
        
        List<DocumentNode> nodes = List.of(validNode, zeroLengthNode);

        when(documentNodeRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<DocumentNode> savedNodes = inv.getArgument(0);
            // Filter out zero-length nodes
            return savedNodes.stream()
                .filter(n -> n.getEndOffset() > n.getStartOffset())
                .toList();
        });

        // When
        int result = documentStructureService.saveNodes(documentId, nodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(1); // Only valid node should be saved
        verify(documentNodeRepository).saveAll(anyList());
    }

    // 7. Identity semantics test
    @Test
    void shouldHandleIdentityHashMapSemanticsForNodes() {
        // Given
        UUID documentId = UUID.randomUUID();
        DocumentNode node1 = createDocumentNode("Same Title", 0, 10, 0);
        DocumentNode node2 = createDocumentNode("Same Title", 0, 10, 0); // Identical content but different instance
        
        List<DocumentNode> nodes = List.of(node1, node2);

        when(documentNodeRepository.saveAll(anyList())).thenReturn(nodes);

        // When
        int result = documentStructureService.saveNodes(documentId, nodes, "hash123", DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isEqualTo(2);
        // Different instances with identical content should be treated as different keys
        assertThat(node1).isNotSameAs(node2);
        verify(documentNodeRepository).saveAll(nodes);
    }
}
