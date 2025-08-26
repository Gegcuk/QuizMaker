package uk.gegc.quizmaker.features.document.domain.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
class DocumentNodeRepositoryTest {

    @Autowired
    private DocumentNodeRepository documentNodeRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void testSaveAndRetrieveDocumentNode() {
        // Create and persist User
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("{noop}password");
        user.setVersion(1L);
        entityManager.persist(user);
        entityManager.flush();

        // Create and persist Document
        Document document = new Document();
        document.setTitle("Test Document");
        document.setOriginalFilename("test.pdf");
        document.setContentType("application/pdf");
        document.setFilePath("/path/to/file");
        document.setFileSize(1024L);
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedBy(user);
        entityManager.persist(document);
        entityManager.flush();

        // Create DocumentNode
        DocumentNode node = new DocumentNode();
        node.setDocument(document);
        node.setLevel(0);
        node.setType(DocumentNode.NodeType.DOCUMENT);
        node.setTitle("Test Node");
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setOrdinal(1);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setSourceVersionHash("test-hash-123");
        node.setCreatedAt(Instant.now());

        // Save and flush to ensure @CreationTimestamp is applied
        DocumentNode savedNode = documentNodeRepository.save(node);
        entityManager.flush();
        entityManager.clear();

        // Retrieve and verify
        DocumentNode retrievedNode = documentNodeRepository.findById(savedNode.getId()).orElse(null);
        assertNotNull(retrievedNode);
        assertEquals("Test Node", retrievedNode.getTitle());
        assertEquals(0, retrievedNode.getLevel());
        assertEquals(DocumentNode.NodeType.DOCUMENT, retrievedNode.getType());
        assertNotNull(retrievedNode.getCreatedAt());
    }

    @Test
    void testFindBySourceVersionHash() {
        // Create and persist User
        User user = new User();
        user.setUsername("testuser2");
        user.setEmail("test2@example.com");
        user.setHashedPassword("{noop}password");
        user.setVersion(1L);
        entityManager.persist(user);
        entityManager.flush();

        // Create and persist Document
        Document document = new Document();
        document.setTitle("Test Document 2");
        document.setOriginalFilename("test2.pdf");
        document.setContentType("application/pdf");
        document.setFilePath("/path/to/file2");
        document.setFileSize(2048L);
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedBy(user);
        entityManager.persist(document);
        entityManager.flush();

        // Create DocumentNode with specific hash
        DocumentNode node = new DocumentNode();
        node.setDocument(document);
        node.setLevel(1);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Test Chapter");
        node.setStartOffset(0);
        node.setEndOffset(200);
        node.setOrdinal(1);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setSourceVersionHash("unique-hash-456");
        node.setCreatedAt(Instant.now());

        documentNodeRepository.save(node);

        // Test findBySourceVersionHash
        List<DocumentNode> foundNodes = documentNodeRepository.findBySourceVersionHash("unique-hash-456");
        assertNotNull(foundNodes);
        assertEquals(1, foundNodes.size());
        DocumentNode foundNode = foundNodes.get(0);
        assertEquals("unique-hash-456", foundNode.getSourceVersionHash());
        assertEquals("Test Chapter", foundNode.getTitle());
    }

    @Test
    void testFindNextOrdinalForParent() {
        // Create and persist User
        User user = new User();
        user.setUsername("testuser3");
        user.setEmail("test3@example.com");
        user.setHashedPassword("{noop}password");
        user.setVersion(1L);
        entityManager.persist(user);
        entityManager.flush();

        // Create and persist Document
        Document document = new Document();
        document.setTitle("Test Document 3");
        document.setOriginalFilename("test3.pdf");
        document.setContentType("application/pdf");
        document.setFilePath("/path/to/file3");
        document.setFileSize(3072L);
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedBy(user);
        entityManager.persist(document);
        entityManager.flush();

        // Create parent node
        DocumentNode parentNode = new DocumentNode();
        parentNode.setDocument(document);
        parentNode.setLevel(0);
        parentNode.setType(DocumentNode.NodeType.DOCUMENT);
        parentNode.setTitle("Parent Node");
        parentNode.setStartOffset(0);
        parentNode.setEndOffset(500);
        parentNode.setOrdinal(1);
        parentNode.setStrategy(DocumentNode.Strategy.AI);
        parentNode.setSourceVersionHash("parent-hash");
        parentNode.setCreatedAt(Instant.now());
        DocumentNode savedParent = documentNodeRepository.save(parentNode);

        // Create child nodes with different ordinals
        DocumentNode child1 = new DocumentNode();
        child1.setDocument(document);
        child1.setParent(savedParent);
        child1.setLevel(1);
        child1.setType(DocumentNode.NodeType.CHAPTER);
        child1.setTitle("Child 1");
        child1.setStartOffset(0);
        child1.setEndOffset(100);
        child1.setOrdinal(1);
        child1.setStrategy(DocumentNode.Strategy.AI);
        child1.setSourceVersionHash("child1-hash");
        child1.setCreatedAt(Instant.now());
        documentNodeRepository.save(child1);

        DocumentNode child2 = new DocumentNode();
        child2.setDocument(document);
        child2.setParent(savedParent);
        child2.setLevel(1);
        child2.setType(DocumentNode.NodeType.CHAPTER);
        child2.setTitle("Child 2");
        child2.setStartOffset(100);
        child2.setEndOffset(200);
        child2.setOrdinal(2);
        child2.setStrategy(DocumentNode.Strategy.AI);
        child2.setSourceVersionHash("child2-hash");
        child2.setCreatedAt(Instant.now());
        documentNodeRepository.save(child2);

        // Test findNextOrdinalForParent
        Integer nextOrdinal = documentNodeRepository.findNextOrdinalForParent(document.getId(), savedParent.getId());
        assertEquals(3, nextOrdinal); // Should be max(1,2) + 1 = 3
    }

    @Test
    void testFindOverlapping() {
        // Given
        Document document = new Document();
        document.setOriginalFilename("test.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1024L);
        document.setFilePath("/uploads/test.pdf");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setTitle("Test Document");
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());

        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("{noop}password");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(false);
        user.setVersion(1L);
        entityManager.persist(user);
        entityManager.flush();
        User savedUser = user;
        document.setUploadedBy(savedUser);

        Document savedDocument = entityManager.merge(document);

        // Create nodes with overlapping ranges
        DocumentNode node1 = new DocumentNode();
        node1.setDocument(savedDocument);
        node1.setLevel(1);
        node1.setType(DocumentNode.NodeType.CHAPTER);
        node1.setTitle("Chapter 1");
        node1.setStartOffset(0);
        node1.setEndOffset(100);
        node1.setOrdinal(1);
        node1.setStrategy(DocumentNode.Strategy.AI);
        node1.setSourceVersionHash("hash1");
        documentNodeRepository.save(node1);

        DocumentNode node2 = new DocumentNode();
        node2.setDocument(savedDocument);
        node2.setLevel(2);
        node2.setType(DocumentNode.NodeType.SECTION);
        node2.setTitle("Section 1");
        node2.setStartOffset(50);
        node2.setEndOffset(150);
        node2.setOrdinal(1);
        node2.setStrategy(DocumentNode.Strategy.AI);
        node2.setSourceVersionHash("hash2");
        documentNodeRepository.save(node2);

        DocumentNode node3 = new DocumentNode();
        node3.setDocument(savedDocument);
        node3.setLevel(2);
        node3.setType(DocumentNode.NodeType.SECTION);
        node3.setTitle("Section 2");
        node3.setStartOffset(200);
        node3.setEndOffset(300);
        node3.setOrdinal(2);
        node3.setStrategy(DocumentNode.Strategy.AI);
        node3.setSourceVersionHash("hash3");
        documentNodeRepository.save(node3);

        // When - search for nodes overlapping with range 25-125
        List<DocumentNode> overlappingNodes = documentNodeRepository.findOverlapping(savedDocument.getId(), 25, 125);

        // Then - should find node1 (0-100) and node2 (50-150) as they overlap with 25-125
        assertEquals(2, overlappingNodes.size());
        assertTrue(overlappingNodes.stream().anyMatch(n -> n.getTitle().equals("Chapter 1")));
        assertTrue(overlappingNodes.stream().anyMatch(n -> n.getTitle().equals("Section 1")));
        assertFalse(overlappingNodes.stream().anyMatch(n -> n.getTitle().equals("Section 2")));
    }

    @Test
    void testFindByDocumentIdOrderByStartOffset() {
        // Given
        Document document = new Document();
        document.setOriginalFilename("test.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1024L);
        document.setFilePath("/uploads/test.pdf");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setTitle("Test Document");
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());

        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("{noop}password");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(false);
        user.setVersion(1L);
        entityManager.persist(user);
        entityManager.flush();
        User savedUser = user;
        document.setUploadedBy(savedUser);

        Document savedDocument = entityManager.merge(document);

        // Create nodes with different start offsets
        DocumentNode node1 = new DocumentNode();
        node1.setDocument(savedDocument);
        node1.setLevel(1);
        node1.setType(DocumentNode.NodeType.CHAPTER);
        node1.setTitle("Chapter 1");
        node1.setStartOffset(100);
        node1.setEndOffset(200);
        node1.setOrdinal(1);
        node1.setStrategy(DocumentNode.Strategy.AI);
        node1.setSourceVersionHash("hash1");
        documentNodeRepository.save(node1);

        DocumentNode node2 = new DocumentNode();
        node2.setDocument(savedDocument);
        node2.setLevel(1);
        node2.setType(DocumentNode.NodeType.CHAPTER);
        node2.setTitle("Chapter 2");
        node2.setStartOffset(0);
        node2.setEndOffset(100);
        node2.setOrdinal(2);
        node2.setStrategy(DocumentNode.Strategy.AI);
        node2.setSourceVersionHash("hash2");
        documentNodeRepository.save(node2);

        DocumentNode node3 = new DocumentNode();
        node3.setDocument(savedDocument);
        node3.setLevel(1);
        node3.setType(DocumentNode.NodeType.CHAPTER);
        node3.setTitle("Chapter 3");
        node3.setStartOffset(50);
        node3.setEndOffset(150);
        node3.setOrdinal(3);
        node3.setStrategy(DocumentNode.Strategy.AI);
        node3.setSourceVersionHash("hash3");
        documentNodeRepository.save(node3);

        // When
        List<DocumentNode> nodes = documentNodeRepository.findByDocumentIdOrderByStartOffset(savedDocument.getId());

        // Then - should be ordered by startOffset: 0, 50, 100
        assertEquals(3, nodes.size());
        assertEquals("Chapter 2", nodes.get(0).getTitle()); // startOffset: 0
        assertEquals("Chapter 3", nodes.get(1).getTitle()); // startOffset: 50
        assertEquals("Chapter 1", nodes.get(2).getTitle()); // startOffset: 100
    }
}
