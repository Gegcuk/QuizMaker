package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.api.dto.FlatNode;
import uk.gegc.quizmaker.features.documentProcess.api.dto.NodeView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StructureService Tests")
class StructureServiceTest {

    @Mock
    private DocumentNodeRepository nodeRepository;

    @Mock
    private NormalizedDocumentRepository documentRepository;

    @Mock
    private DocumentNodeMapper nodeMapper;

    @InjectMocks
    private StructureService service;

    private UUID documentId;
    private NormalizedDocument document;
    private DocumentNode rootNode;
    private DocumentNode childNode;
    private NodeView rootNodeView;
    private NodeView childNodeView;
    private FlatNode rootFlatNode;
    private FlatNode childFlatNode;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("test.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        
        rootNode = new DocumentNode();
        rootNode.setId(UUID.randomUUID());
        rootNode.setDocument(document);
        rootNode.setParent(null);
        rootNode.setIdx(1);
        rootNode.setType(DocumentNode.NodeType.CHAPTER);
        rootNode.setTitle("Chapter 1");
        rootNode.setStartOffset(0);
        rootNode.setEndOffset(100);
        rootNode.setDepth((short) 0);
        
        childNode = new DocumentNode();
        childNode.setId(UUID.randomUUID());
        childNode.setDocument(document);
        childNode.setParent(rootNode);
        childNode.setIdx(1);
        childNode.setType(DocumentNode.NodeType.SECTION);
        childNode.setTitle("Section 1.1");
        childNode.setStartOffset(10);
        childNode.setEndOffset(50);
        childNode.setDepth((short) 1);
        
        rootNodeView = new NodeView(
                rootNode.getId(), documentId, null, 1, DocumentNode.NodeType.CHAPTER,
                "Chapter 1", 0, 100, (short) 0, null, null, List.of()
        );
        
        childNodeView = new NodeView(
                childNode.getId(), documentId, rootNode.getId(), 1, DocumentNode.NodeType.SECTION,
                "Section 1.1", 10, 50, (short) 1, null, null, List.of()
        );
        
        rootFlatNode = new FlatNode(
                rootNode.getId(), documentId, null, 1, DocumentNode.NodeType.CHAPTER,
                "Chapter 1", 0, 100, (short) 0, null, null
        );
        
        childFlatNode = new FlatNode(
                childNode.getId(), documentId, rootNode.getId(), 1, DocumentNode.NodeType.SECTION,
                "Section 1.1", 10, 50, (short) 1, null, null
        );
    }

    @Test
    @DisplayName("getTree_documentExists_returnsTreeResponseWithTotalNodes")
    void getTree_documentExists_returnsTreeResponseWithTotalNodes() {
        // Given
        List<DocumentNode> nodes = List.of(rootNode, childNode);
        List<NodeView> treeNodes = List.of(rootNodeView);
        
        when(documentRepository.existsById(documentId)).thenReturn(true);
        when(nodeRepository.findAllForTree(documentId)).thenReturn(nodes);
        when(nodeMapper.buildTree(nodes)).thenReturn(treeNodes);

        // When
        StructureTreeResponse result = service.getTree(documentId);

        // Then
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.rootNodes()).isEqualTo(treeNodes);
        assertThat(result.totalNodes()).isEqualTo(2);
    }

    @Test
    @DisplayName("getTree_documentMissing_throwsResourceNotFound")
    void getTree_documentMissing_throwsResourceNotFound() {
        // Given
        when(documentRepository.existsById(documentId)).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> service.getTree(documentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found: " + documentId);
    }

    @Test
    @DisplayName("getFlat_documentExists_returnsFlatResponseWithTotalNodes")
    void getFlat_documentExists_returnsFlatResponseWithTotalNodes() {
        // Given
        List<DocumentNode> nodes = List.of(rootNode, childNode);
        List<FlatNode> flatNodes = List.of(rootFlatNode, childFlatNode);
        
        when(documentRepository.existsById(documentId)).thenReturn(true);
        when(nodeRepository.findAllByDocumentIdOrderByStartOffset(documentId)).thenReturn(nodes);
        when(nodeRepository.countByDocumentId(documentId)).thenReturn(2L);
        when(nodeMapper.toFlatNodeList(nodes)).thenReturn(flatNodes);

        // When
        StructureFlatResponse result = service.getFlat(documentId);

        // Then
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.nodes()).isEqualTo(flatNodes);
        assertThat(result.totalNodes()).isEqualTo(2);
    }

    @Test
    @DisplayName("getFlat_documentMissing_throwsResourceNotFound")
    void getFlat_documentMissing_throwsResourceNotFound() {
        // Given
        when(documentRepository.existsById(documentId)).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> service.getFlat(documentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found: " + documentId);
    }

    @Test
    @DisplayName("buildStructure_throwsUnsupportedOperation")
    void buildStructure_throwsUnsupportedOperation() {
        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Structure building will be implemented in Phase 3");
    }

    @Test
    @DisplayName("getTree_emptyStructure_returnsEmptyResponse")
    void getTree_emptyStructure_returnsEmptyResponse() {
        // Given
        when(documentRepository.existsById(documentId)).thenReturn(true);
        when(nodeRepository.findAllForTree(documentId)).thenReturn(List.of());
        when(nodeMapper.buildTree(List.of())).thenReturn(List.of());

        // When
        StructureTreeResponse result = service.getTree(documentId);

        // Then
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.rootNodes()).isEmpty();
        assertThat(result.totalNodes()).isEqualTo(0);
    }

    @Test
    @DisplayName("getFlat_emptyStructure_returnsEmptyResponse")
    void getFlat_emptyStructure_returnsEmptyResponse() {
        // Given
        when(documentRepository.existsById(documentId)).thenReturn(true);
        when(nodeRepository.findAllByDocumentIdOrderByStartOffset(documentId)).thenReturn(List.of());
        when(nodeRepository.countByDocumentId(documentId)).thenReturn(0L);
        when(nodeMapper.toFlatNodeList(List.of())).thenReturn(List.of());

        // When
        StructureFlatResponse result = service.getFlat(documentId);

        // Then
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.nodes()).isEmpty();
        assertThat(result.totalNodes()).isEqualTo(0);
    }
}
