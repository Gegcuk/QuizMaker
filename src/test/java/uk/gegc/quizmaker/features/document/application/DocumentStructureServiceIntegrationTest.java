package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Transactional
class DocumentStructureServiceIntegrationTest {

    @Autowired
    private DocumentStructureService documentStructureService;

    @Autowired
    private DocumentNodeRepository documentNodeRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldExtractAndAlignDocumentStructureIntegration() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        // Create a test document in the database
        uk.gegc.quizmaker.features.document.domain.model.Document document = 
            new uk.gegc.quizmaker.features.document.domain.model.Document();
        document.setId(documentId);
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        
        // Note: In a real integration test, you would save the document first
        // For this test, we'll focus on the structure service functionality
        
        // When
        // This would normally call the full pipeline, but for integration testing
        // we'll test the individual components separately
        
        // Test structure management methods
        boolean hasStructure = documentStructureService.hasDocumentStructure(documentId);
        assertThat(hasStructure).isFalse();
        
        long count = documentStructureService.getDocumentStructureCount(documentId);
        assertThat(count).isEqualTo(0);
        
        List<DocumentNode> treeStructure = documentStructureService.getDocumentStructureTree(documentId);
        assertThat(treeStructure).isEmpty();
        
        List<DocumentNode> flatStructure = documentStructureService.getDocumentStructureFlat(documentId);
        assertThat(flatStructure).isEmpty();
    }

    @Test
    void shouldHandleDocumentStructureOperations() {
        // Given
        // Create and save a test user first
        User user = createTestUser();
        
        // Create and save a test document first
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();

        // Create test document nodes
        DocumentNode rootNode = createTestDocumentNode(documentId, "Chapter 1", 0, 100, 0, null);
        
        // Save root node first
        rootNode = documentNodeRepository.saveAndFlush(rootNode);
        
        // Create child node with saved parent
        DocumentNode childNode = createTestDocumentNode(documentId, "Section 1.1", 10, 50, 1, rootNode);
        
        // Save child node
        childNode = documentNodeRepository.saveAndFlush(childNode);
        
        // When & Then
        boolean hasStructure = documentStructureService.hasDocumentStructure(documentId);
        assertThat(hasStructure).isTrue();
        
        long count = documentStructureService.getDocumentStructureCount(documentId);
        assertThat(count).isEqualTo(2);
        
        List<DocumentNode> treeStructure = documentStructureService.getDocumentStructureTree(documentId);
        assertThat(treeStructure).hasSize(1);
        assertThat(treeStructure.get(0).getTitle()).isEqualTo("Chapter 1");
        assertThat(treeStructure.get(0).getChildren()).hasSize(1);
        assertThat(treeStructure.get(0).getChildren().get(0).getTitle()).isEqualTo("Section 1.1");
        
        List<DocumentNode> flatStructure = documentStructureService.getDocumentStructureFlat(documentId);
        assertThat(flatStructure).hasSize(2);
        assertThat(flatStructure.get(0).getTitle()).isEqualTo("Chapter 1");
        assertThat(flatStructure.get(1).getTitle()).isEqualTo("Section 1.1");
        
        // Test finding nodes by type
        List<DocumentNode> chapters = documentStructureService.findNodesByType(documentId, DocumentNode.NodeType.CHAPTER);
        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).getTitle()).isEqualTo("Chapter 1");
        
        // Test finding overlapping nodes
        List<DocumentNode> overlapping = documentStructureService.findOverlappingNodes(documentId, 5, 55);
        assertThat(overlapping).hasSize(2);
        
        // Test deletion
        documentStructureService.deleteDocumentStructure(documentId);
        assertThat(documentStructureService.hasDocumentStructure(documentId)).isFalse();
    }

    @Test
    void shouldHandleComplexHierarchicalStructure() {
        // Given
        // Create and save a test user first
        User user = createTestUser();
        
        // Create and save a test document first
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create a complex hierarchical structure
        DocumentNode part = createTestDocumentNode(documentId, "Part 1", 0, 200, 0, null);
        part.setType(DocumentNode.NodeType.PART);
        
        // Save part first
        part = documentNodeRepository.save(part);
        
        DocumentNode chapter = createTestDocumentNode(documentId, "Chapter 1", 10, 150, 1, part);
        chapter.setType(DocumentNode.NodeType.CHAPTER);
        
        // Save chapter
        chapter = documentNodeRepository.save(chapter);
        
        DocumentNode section = createTestDocumentNode(documentId, "Section 1.1", 20, 100, 2, chapter);
        section.setType(DocumentNode.NodeType.SECTION);
        
        // Save section
        section = documentNodeRepository.save(section);
        
        DocumentNode subsection = createTestDocumentNode(documentId, "Subsection 1.1.1", 25, 80, 3, section);
        subsection.setType(DocumentNode.NodeType.SUBSECTION);
        
        // Save subsection
        documentNodeRepository.save(subsection);
        
        // When
        List<DocumentNode> treeStructure = documentStructureService.getDocumentStructureTree(documentId);
        
        // Then
        assertThat(treeStructure).hasSize(1);
        assertThat(treeStructure.get(0).getTitle()).isEqualTo("Part 1");
        assertThat(treeStructure.get(0).getType()).isEqualTo(DocumentNode.NodeType.PART);
        
        // Test finding by type
        List<DocumentNode> parts = documentStructureService.findNodesByType(documentId, DocumentNode.NodeType.PART);
        assertThat(parts).hasSize(1);
        
        List<DocumentNode> chapters = documentStructureService.findNodesByType(documentId, DocumentNode.NodeType.CHAPTER);
        assertThat(chapters).hasSize(1);
        
        List<DocumentNode> sections = documentStructureService.findNodesByType(documentId, DocumentNode.NodeType.SECTION);
        assertThat(sections).hasSize(1);
        
        List<DocumentNode> subsections = documentStructureService.findNodesByType(documentId, DocumentNode.NodeType.SUBSECTION);
        assertThat(subsections).hasSize(1);
    }

    @Test
    void shouldHandleOverlappingNodes() {
        // Given
        // Create and save a test user first
        User user = createTestUser();
        
        // Create and save a test document first
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        DocumentNode node1 = createTestDocumentNode(documentId, "Node 1", 0, 50, 0, null);
        DocumentNode node2 = createTestDocumentNode(documentId, "Node 2", 25, 75, 0, null);
        DocumentNode node3 = createTestDocumentNode(documentId, "Node 3", 100, 150, 0, null);
        
        documentNodeRepository.saveAll(List.of(node1, node2, node3));
        
        // When & Then
        // Test overlapping range
        List<DocumentNode> overlapping = documentStructureService.findOverlappingNodes(documentId, 10, 60);
        assertThat(overlapping).hasSize(2);
        assertThat(overlapping).extracting("title").contains("Node 1", "Node 2");
        
        // Test non-overlapping range
        List<DocumentNode> nonOverlapping = documentStructureService.findOverlappingNodes(documentId, 60, 90);
        assertThat(nonOverlapping).hasSize(1);
        assertThat(nonOverlapping.get(0).getTitle()).isEqualTo("Node 2");
        
        // Test range that includes all nodes
        List<DocumentNode> allNodes = documentStructureService.findOverlappingNodes(documentId, 0, 200);
        assertThat(allNodes).hasSize(3);
    }

    private User createTestUser() {
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("hashedpassword");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private DocumentNode createTestDocumentNode(UUID documentId, String title, int startOffset, int endOffset, int level, DocumentNode parent) {
        DocumentNode node = new DocumentNode();
        node.setTitle(title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setLevel(level);
        
        // Set appropriate type based on level
        if (level == 0) {
            node.setType(DocumentNode.NodeType.CHAPTER);
        } else if (level == 1) {
            node.setType(DocumentNode.NodeType.SECTION);
        } else if (level == 2) {
            node.setType(DocumentNode.NodeType.SUBSECTION);
        } else {
            node.setType(DocumentNode.NodeType.SUBSECTION);
        }
        
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setConfidence(java.math.BigDecimal.valueOf(0.8));
        node.setSourceVersionHash("test-hash");
        node.setOrdinal(1);
        node.setParent(parent);
        
        // Set up document reference
        uk.gegc.quizmaker.features.document.domain.model.Document document = 
            new uk.gegc.quizmaker.features.document.domain.model.Document();
        document.setId(documentId);
        node.setDocument(document);
        
        return node;
    }

    // 1. Deep recursion test (>2 levels)
    @Test
    void shouldHandleDeepRecursionWithMoreThanTwoLevels() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create a deep hierarchical structure with >2 levels
        DocumentNode part = createTestDocumentNode(documentId, "Part 1: Introduction", 0, 300, 0, null);
        part.setType(DocumentNode.NodeType.PART);
        part.setOrdinal(1);
        part = documentNodeRepository.save(part);
        
        DocumentNode chapter = createTestDocumentNode(documentId, "Chapter 1: Background", 10, 250, 1, part);
        chapter.setType(DocumentNode.NodeType.CHAPTER);
        chapter.setOrdinal(1);
        chapter = documentNodeRepository.save(chapter);
        
        DocumentNode section = createTestDocumentNode(documentId, "Section 1.1: History", 20, 200, 2, chapter);
        section.setType(DocumentNode.NodeType.SECTION);
        section.setOrdinal(1);
        section = documentNodeRepository.save(section);
        
        DocumentNode subsection = createTestDocumentNode(documentId, "Subsection 1.1.1: Early History", 25, 150, 3, section);
        subsection.setType(DocumentNode.NodeType.SUBSECTION);
        subsection.setOrdinal(1);
        subsection = documentNodeRepository.save(subsection);
        
        DocumentNode paragraph = createTestDocumentNode(documentId, "Paragraph 1.1.1.1: Details", 30, 100, 4, subsection);
        paragraph.setType(DocumentNode.NodeType.PARAGRAPH);
        paragraph.setOrdinal(1);
        paragraph = documentNodeRepository.save(paragraph);
        
        DocumentNode subparagraph = createTestDocumentNode(documentId, "Subparagraph 1.1.1.1.1: Specifics", 35, 80, 5, paragraph);
        subparagraph.setType(DocumentNode.NodeType.PARAGRAPH);
        subparagraph.setOrdinal(1);
        documentNodeRepository.save(subparagraph);
        
        // When
        List<DocumentNode> treeStructure = documentStructureService.getDocumentStructureTree(documentId);
        
        // Then
        assertThat(treeStructure).hasSize(1);
        assertThat(treeStructure.get(0).getTitle()).isEqualTo("Part 1: Introduction");
        assertThat(treeStructure.get(0).getType()).isEqualTo(DocumentNode.NodeType.PART);
        
        // Verify deep recursion works
        DocumentNode root = treeStructure.get(0);
        assertThat(root.getChildren()).hasSize(1);
        assertThat(root.getChildren().get(0).getChildren()).hasSize(1);
        assertThat(root.getChildren().get(0).getChildren().get(0).getChildren()).hasSize(1);
        assertThat(root.getChildren().get(0).getChildren().get(0).getChildren().get(0).getChildren()).hasSize(1);
        assertThat(root.getChildren().get(0).getChildren().get(0).getChildren().get(0).getChildren().get(0).getChildren()).hasSize(1);
    }

    // 2. Complex hierarchical structure with multiple branches
    @Test
    void shouldHandleComplexHierarchicalStructureWithMultipleBranches() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create a complex structure with multiple branches
        DocumentNode part = createTestDocumentNode(documentId, "Part 1", 0, 400, 0, null);
        part.setType(DocumentNode.NodeType.PART);
        part.setOrdinal(1);
        part = documentNodeRepository.save(part);
        
        // First branch
        DocumentNode chapter1 = createTestDocumentNode(documentId, "Chapter 1", 10, 200, 1, part);
        chapter1.setType(DocumentNode.NodeType.CHAPTER);
        chapter1.setOrdinal(1);
        chapter1 = documentNodeRepository.save(chapter1);
        
        DocumentNode section1 = createTestDocumentNode(documentId, "Section 1.1", 20, 100, 2, chapter1);
        section1.setType(DocumentNode.NodeType.SECTION);
        section1.setOrdinal(1);
        section1 = documentNodeRepository.save(section1);
        
        DocumentNode subsection1 = createTestDocumentNode(documentId, "Subsection 1.1.1", 25, 80, 3, section1);
        subsection1.setType(DocumentNode.NodeType.SUBSECTION);
        subsection1.setOrdinal(1);
        documentNodeRepository.save(subsection1);
        
        // Second branch
        DocumentNode chapter2 = createTestDocumentNode(documentId, "Chapter 2", 210, 350, 1, part);
        chapter2.setType(DocumentNode.NodeType.CHAPTER);
        chapter2.setOrdinal(2);
        chapter2 = documentNodeRepository.save(chapter2);
        
        DocumentNode section2 = createTestDocumentNode(documentId, "Section 2.1", 220, 300, 2, chapter2);
        section2.setType(DocumentNode.NodeType.SECTION);
        section2.setOrdinal(1);
        documentNodeRepository.save(section2);
        
        // When
        List<DocumentNode> treeStructure = documentStructureService.getDocumentStructureTree(documentId);
        
        // Then
        assertThat(treeStructure).hasSize(1);
        DocumentNode root = treeStructure.get(0);
        assertThat(root.getChildren()).hasSize(2);
        
        // Verify first branch
        DocumentNode firstChapter = root.getChildren().get(0);
        assertThat(firstChapter.getTitle()).isEqualTo("Chapter 1");
        assertThat(firstChapter.getChildren()).hasSize(1);
        assertThat(firstChapter.getChildren().get(0).getChildren()).hasSize(1);
        
        // Verify second branch
        DocumentNode secondChapter = root.getChildren().get(1);
        assertThat(secondChapter.getTitle()).isEqualTo("Chapter 2");
        assertThat(secondChapter.getChildren()).hasSize(1);
    }

    // 3. Boundary cases for overlapping nodes
    @Test
    void shouldHandleBoundaryCasesForOverlappingNodes() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create nodes with exact boundary overlaps
        DocumentNode node1 = createTestDocumentNode(documentId, "Node 1", 0, 50, 0, null);
        DocumentNode node2 = createTestDocumentNode(documentId, "Node 2", 50, 100, 0, null); // Exact boundary
        DocumentNode node3 = createTestDocumentNode(documentId, "Node 3", 25, 75, 0, null); // Overlapping
        
        documentNodeRepository.saveAll(List.of(node1, node2, node3));
        
        // When & Then
        // Test exact boundary with half-open semantics [start, end)
        // Node 1 [0,50) overlaps [0,50) ✓
        // Node 3 [25,75) overlaps [0,50) ✓  
        // Node 2 [50,100) does NOT overlap [0,50) because it starts exactly at endOffset=50 ✗
        List<DocumentNode> exactBoundary = documentStructureService.findOverlappingNodes(documentId, 0, 50);
        assertThat(exactBoundary).hasSize(2);
        assertThat(exactBoundary).extracting("title").containsExactlyInAnyOrder("Node 1", "Node 3");
        
        // Test overlapping range
        List<DocumentNode> overlapping = documentStructureService.findOverlappingNodes(documentId, 30, 60);
        assertThat(overlapping).hasSize(3);
        assertThat(overlapping).extracting("title").contains("Node 1", "Node 3");
        
        // Test range that touches boundaries
        List<DocumentNode> touchingBoundary = documentStructureService.findOverlappingNodes(documentId, 49, 51);
        assertThat(touchingBoundary).hasSize(3);
        assertThat(touchingBoundary).extracting("title").contains("Node 1", "Node 2");
    }

    // 4. Zero-length nodes handling in integration
    @Test
    void shouldHandleZeroLengthNodesInIntegration() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create nodes including zero-length ones
        DocumentNode validNode = createTestDocumentNode(documentId, "Valid Node", 0, 10, 0, null);
        DocumentNode zeroLengthNode = createTestDocumentNode(documentId, "Zero Length", 5, 5, 0, null); // start == end
        DocumentNode anotherValidNode = createTestDocumentNode(documentId, "Another Valid", 10, 20, 0, null);
        
        documentNodeRepository.saveAll(List.of(validNode, zeroLengthNode, anotherValidNode));
        
        // When
        List<DocumentNode> flatStructure = documentStructureService.getDocumentStructureFlat(documentId);
        
        // Then
        // Should include all nodes (filtering happens at service level, not repository level)
        assertThat(flatStructure).hasSize(3);
        
        // Verify zero-length node is present in repository
        assertThat(flatStructure).extracting("title").contains("Zero Length");
    }

    // 5. Identity semantics test in integration
    @Test
    void shouldHandleIdentitySemanticsInIntegration() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create two nodes with identical content but different instances
        DocumentNode node1 = createTestDocumentNode(documentId, "Same Title", 0, 10, 0, null);
        node1.setOrdinal(1);
        DocumentNode node2 = createTestDocumentNode(documentId, "Same Title", 0, 10, 0, null);
        node2.setOrdinal(2);
        
        documentNodeRepository.saveAll(List.of(node1, node2));
        
        // When
        List<DocumentNode> flatStructure = documentStructureService.getDocumentStructureFlat(documentId);
        
        // Then
        assertThat(flatStructure).hasSize(2);
        // Different instances with identical content should be treated as different entities
        assertThat(flatStructure.get(0)).isNotSameAs(flatStructure.get(1));
        assertThat(flatStructure.get(0).getTitle()).isEqualTo(flatStructure.get(1).getTitle());
    }

    // 6. Threshold edges test in integration
    @Test
    void shouldHandleThresholdEdgesInIntegration() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create nodes with different confidence levels
        DocumentNode highConfidenceNode = createTestDocumentNode(documentId, "High Confidence", 0, 50, 0, null);
        highConfidenceNode.setConfidence(java.math.BigDecimal.valueOf(0.95));
        highConfidenceNode.setOrdinal(1);
        
        DocumentNode thresholdNode = createTestDocumentNode(documentId, "Threshold Node", 50, 100, 0, null);
        thresholdNode.setConfidence(java.math.BigDecimal.valueOf(0.80)); // Exactly at threshold
        thresholdNode.setOrdinal(2);
        
        DocumentNode lowConfidenceNode = createTestDocumentNode(documentId, "Low Confidence", 100, 150, 0, null);
        lowConfidenceNode.setConfidence(java.math.BigDecimal.valueOf(0.75)); // Below threshold
        lowConfidenceNode.setOrdinal(3);
        
        documentNodeRepository.saveAll(List.of(highConfidenceNode, thresholdNode, lowConfidenceNode));
        
        // When
        List<DocumentNode> flatStructure = documentStructureService.getDocumentStructureFlat(documentId);
        
        // Then
        assertThat(flatStructure).hasSize(3);
        assertThat(flatStructure.get(0).getConfidence().doubleValue()).isEqualTo(0.95);
        assertThat(flatStructure.get(1).getConfidence().doubleValue()).isEqualTo(0.80);
        assertThat(flatStructure.get(2).getConfidence().doubleValue()).isEqualTo(0.75);
    }

    // 7. Unicode and special characters test in integration
    @Test
    void shouldHandleUnicodeAndSpecialCharactersInIntegration() {
        // Given
        User user = createTestUser();
        
        Document document = new Document();
        document.setOriginalFilename("test-document.txt");
        document.setContentType("text/plain");
        document.setFilePath("test-path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setFileSize(1024L);
        document.setUploadedBy(user);
        documentRepository.saveAndFlush(document);
        UUID documentId = document.getId();
        
        // Create nodes with Unicode and special characters
        DocumentNode unicodeNode = createTestDocumentNode(documentId, "Chapitre 1: Introduction", 0, 50, 0, null);
        unicodeNode.setOrdinal(1);
        DocumentNode specialCharNode = createTestDocumentNode(documentId, "Chapter 1.2: Methods & Results", 50, 100, 0, null);
        specialCharNode.setOrdinal(2);
        DocumentNode cjkNode = createTestDocumentNode(documentId, "第一章：介绍", 100, 150, 0, null);
        cjkNode.setOrdinal(3);
        
        documentNodeRepository.saveAll(List.of(unicodeNode, specialCharNode, cjkNode));
        
        // When
        List<DocumentNode> flatStructure = documentStructureService.getDocumentStructureFlat(documentId);
        
        // Then
        assertThat(flatStructure).hasSize(3);
        assertThat(flatStructure.get(0).getTitle()).isEqualTo("Chapitre 1: Introduction");
        assertThat(flatStructure.get(1).getTitle()).isEqualTo("Chapter 1.2: Methods & Results");
        assertThat(flatStructure.get(2).getTitle()).isEqualTo("第一章：介绍");
    }
}
