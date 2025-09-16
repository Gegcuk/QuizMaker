package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Node Extraction Integration Tests")
class NodeExtractionIntegrationTest {

    @Autowired
    private StructureService structureService;

    @Autowired
    private DocumentNodeRepository nodeRepository;

    @Autowired
    private NormalizedDocumentRepository documentRepository;

    @MockitoBean
    private LlmClient llmClient;

    private UUID documentId;

    @BeforeEach
    void setUp() {
        // Clean up before each test
        nodeRepository.deleteAll();
        documentRepository.deleteAll();
    }

    @Test
    @DisplayName("Should extract text content by node ID with correct boundaries")
    void shouldExtractTextByNodeId() throws Exception {
        // Given: A document with structure
        String text = "Chapter 1: Introduction\n\nThis is the first chapter. It covers basic concepts.\n\nChapter 2: Advanced Topics\n\nThis chapter discusses complex ideas.";
        createTestDocument(text);
        
        // Create test nodes
        List<DocumentNode> testNodes = createTestNodesForExtraction(text);
        when(llmClient.generateStructure(anyString(), any())).thenReturn(testNodes);
        
        // Build structure
        structureService.buildStructure(documentId);
        
        // When: Extract content by node ID
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).hasSize(2);
        
        DocumentNode firstChapter = savedNodes.get(0);
        ExtractResponse extractResponse = structureService.extractByNode(documentId, firstChapter.getId());
        
        // Then: Should return correct content
        assertThat(extractResponse.documentId()).isEqualTo(documentId);
        assertThat(extractResponse.nodeId()).isEqualTo(firstChapter.getId());
        assertThat(extractResponse.title()).isEqualTo("Chapter 1: Introduction");
        assertThat(extractResponse.start()).isEqualTo(0);
        assertThat(extractResponse.end()).isEqualTo(77);
        assertThat(extractResponse.text()).isEqualTo("Chapter 1: Introduction\n\nThis is the first chapter. It covers basic concepts.");
    }
    
    @Test
    @DisplayName("Should extract second chapter content correctly")
    void shouldExtractSecondChapterContent() throws Exception {
        // Given: A document with structure
        String text = "Chapter 1: Introduction\n\nThis is the first chapter. It covers basic concepts.\n\nChapter 2: Advanced Topics\n\nThis chapter discusses complex ideas.";
        createTestDocument(text);
        
        // Create test nodes
        List<DocumentNode> testNodes = createTestNodesForExtraction(text);
        when(llmClient.generateStructure(anyString(), any())).thenReturn(testNodes);
        
        // Build structure
        structureService.buildStructure(documentId);
        
        // When: Extract content by node ID for second chapter
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        DocumentNode secondChapter = savedNodes.get(1);
        ExtractResponse extractResponse = structureService.extractByNode(documentId, secondChapter.getId());
        
        // Then: Should return correct content
        assertThat(extractResponse.documentId()).isEqualTo(documentId);
        assertThat(extractResponse.nodeId()).isEqualTo(secondChapter.getId());
        assertThat(extractResponse.title()).isEqualTo("Chapter 2: Advanced Topics");
        assertThat(extractResponse.start()).isEqualTo(79);
        assertThat(extractResponse.end()).isEqualTo(144);
        assertThat(extractResponse.text()).isEqualTo("Chapter 2: Advanced Topics\n\nThis chapter discusses complex ideas.");
    }
    
    @Test
    @DisplayName("Should throw ResourceNotFoundException for non-existent document")
    void shouldThrowResourceNotFoundExceptionForNonExistentDocument() {
        // Given: Non-existent document and node IDs
        UUID nonExistentDocId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        
        // When/Then: Should throw ResourceNotFoundException
        assertThatThrownBy(() -> structureService.extractByNode(nonExistentDocId, nodeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Document not found: " + nonExistentDocId);
    }
    
    @Test
    @DisplayName("Should throw ResourceNotFoundException for non-existent node")
    void shouldThrowResourceNotFoundExceptionForNonExistentNode() throws Exception {
        // Given: A document without structure
        createTestDocument("Some test content");
        UUID nonExistentNodeId = UUID.randomUUID();
        
        // When/Then: Should throw ResourceNotFoundException
        assertThatThrownBy(() -> structureService.extractByNode(documentId, nonExistentNodeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Node not found: " + nonExistentNodeId);
    }
    
    @Test
    @DisplayName("Should throw IllegalArgumentException when node doesn't belong to document")
    void shouldThrowIllegalArgumentExceptionWhenNodeDoesNotBelongToDocument() throws Exception {
        // Given: Two documents with structures
        String text1 = "Document 1 content";
        String text2 = "Document 2 content";
        
        createTestDocument(text1);
        UUID doc1Id = documentId;
        
        // Create second document
        NormalizedDocument doc2 = new NormalizedDocument();
        doc2.setOriginalName("doc2.txt");
        doc2.setSource(NormalizedDocument.DocumentSource.TEXT);
        doc2.setNormalizedText(text2);
        doc2.setCharCount(text2.length());
        doc2.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        doc2 = documentRepository.save(doc2);
        UUID doc2Id = doc2.getId();
        
        // Build structure for doc2
        List<DocumentNode> testNodes = createSimpleTestNodes(text2);
        when(llmClient.generateStructure(anyString(), any())).thenReturn(testNodes);
        structureService.buildStructure(doc2Id);
        
        // Get a node from doc2
        List<DocumentNode> doc2Nodes = nodeRepository.findByDocument_IdOrderByStartOffset(doc2Id);
        assertThat(doc2Nodes).isNotEmpty();
        UUID doc2NodeId = doc2Nodes.get(0).getId();
        
        // When/Then: Try to extract doc2's node using doc1's ID - should fail
        assertThatThrownBy(() -> structureService.extractByNode(doc1Id, doc2NodeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Node " + doc2NodeId + " does not belong to document " + doc1Id);
    }

    @Test
    @DisplayName("Should handle empty text extraction gracefully")
    void shouldHandleEmptyTextExtraction() throws Exception {
        // Given: A document with a node that has zero-length content
        String text = "Start content";
        createTestDocument(text);
        
        // Create a node with same start and end offsets (empty content)
        List<DocumentNode> testNodes = new ArrayList<>();
        DocumentNode emptyNode = new DocumentNode();
        emptyNode.setType(DocumentNode.NodeType.PARAGRAPH);
        emptyNode.setTitle("Empty Node");
        emptyNode.setStartAnchor("Start");
        emptyNode.setEndAnchor("Start");  // Same anchor for zero-length content
        emptyNode.setDepth((short) 0);
        emptyNode.setAiConfidence(BigDecimal.valueOf(0.9));
        testNodes.add(emptyNode);
        
        when(llmClient.generateStructure(anyString(), any())).thenReturn(testNodes);
        
        // Build structure
        structureService.buildStructure(documentId);
        
        // When: Extract the empty content
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).hasSize(1);
        
        DocumentNode savedNode = savedNodes.get(0);
        // Manually set same offsets to simulate empty content
        savedNode.setStartOffset(0);
        savedNode.setEndOffset(0);
        nodeRepository.save(savedNode);
        
        ExtractResponse extractResponse = structureService.extractByNode(documentId, savedNode.getId());
        
        // Then: Should return empty text
        assertThat(extractResponse.text()).isEmpty();
        assertThat(extractResponse.start()).isEqualTo(0);
        assertThat(extractResponse.end()).isEqualTo(0);
    }
    
    // Helper method to create test document
    private void createTestDocument(String text) {
        NormalizedDocument document = new NormalizedDocument();
        document.setOriginalName("test.txt");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setNormalizedText(text);
        document.setCharCount(text.length());
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document = documentRepository.save(document);
        this.documentId = document.getId();
    }
    
    // Helper method to create test nodes for extraction testing
    private List<DocumentNode> createTestNodesForExtraction(String text) {
        List<DocumentNode> nodes = new ArrayList<>();
        
        // Chapter 1: Introduction (0-68)
        DocumentNode chapter1 = new DocumentNode();
        chapter1.setType(DocumentNode.NodeType.CHAPTER);
        chapter1.setTitle("Chapter 1: Introduction");
        chapter1.setStartAnchor("Chapter 1: Introduction");
        chapter1.setEndAnchor("basic concepts.");
        chapter1.setDepth((short) 0);
        chapter1.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(chapter1);
        
        // Chapter 2: Advanced Topics (70-135)
        DocumentNode chapter2 = new DocumentNode();
        chapter2.setType(DocumentNode.NodeType.CHAPTER);
        chapter2.setTitle("Chapter 2: Advanced Topics");
        chapter2.setStartAnchor("Chapter 2: Advanced Topics");
        chapter2.setEndAnchor("complex ideas.");
        chapter2.setDepth((short) 0);
        chapter2.setAiConfidence(BigDecimal.valueOf(0.92));
        nodes.add(chapter2);
        
        return nodes;
    }
    
    // Helper method to create simple test nodes for a document
    private List<DocumentNode> createSimpleTestNodes(String text) {
        List<DocumentNode> nodes = new ArrayList<>();
        
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.PARAGRAPH);
        node.setTitle("Content");
        node.setStartAnchor(text.substring(0, Math.min(20, text.length())));
        node.setEndAnchor(text.substring(Math.max(0, text.length() - 20)));
        node.setDepth((short) 0);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(node);
        
        return nodes;
    }
}
