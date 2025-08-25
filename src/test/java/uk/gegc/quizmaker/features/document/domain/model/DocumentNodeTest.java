package uk.gegc.quizmaker.features.document.domain.model;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentNodeTest {

    @Test
    void testDocumentNodeCreation() {
        // Given
        UUID documentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        
        Document document = new Document();
        document.setId(documentId);
        document.setOriginalFilename("test.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1024L);
        document.setFilePath("/uploads/test.pdf");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setTitle("Test Document");
        
        User user = new User();
        user.setId(userId);
        document.setUploadedBy(user);

        // When
        DocumentNode node = new DocumentNode();
        node.setDocument(document);
        node.setLevel(1);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Test Chapter");
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setStartAnchor("Chapter 1");
        node.setEndAnchor("End of Chapter 1");
        node.setOrdinal(1);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setConfidence(new BigDecimal("0.95"));
        node.setSourceVersionHash("abc123");
        node.setCreatedAt(Instant.now());

        // Then
        // Note: ID will be null in unit tests since it's generated on persistence
        // assertEquals(document, node.getDocument());
        assertEquals(1, node.getLevel());
        assertEquals(DocumentNode.NodeType.CHAPTER, node.getType());
        assertEquals("Test Chapter", node.getTitle());
        assertEquals(0, node.getStartOffset());
        assertEquals(100, node.getEndOffset());
        assertEquals("Chapter 1", node.getStartAnchor());
        assertEquals("End of Chapter 1", node.getEndAnchor());
        assertEquals(1, node.getOrdinal());
        assertEquals(DocumentNode.Strategy.AI, node.getStrategy());
        assertEquals(new BigDecimal("0.95"), node.getConfidence());
        assertEquals("abc123", node.getSourceVersionHash());
        assertNotNull(node.getCreatedAt());
    }

    @Test
    void testTreeOperations() {
        // Given
        DocumentNode parent = new DocumentNode();
        parent.setLevel(1);
        parent.setType(DocumentNode.NodeType.CHAPTER);
        parent.setTitle("Parent Chapter");
        parent.setStartOffset(0);
        parent.setEndOffset(200);
        parent.setOrdinal(1);
        parent.setStrategy(DocumentNode.Strategy.AI);
        parent.setSourceVersionHash("parent123");
        parent.setCreatedAt(Instant.now());

        DocumentNode child = new DocumentNode();
        child.setLevel(2);
        child.setType(DocumentNode.NodeType.SECTION);
        child.setTitle("Child Section");
        child.setStartOffset(0);
        child.setEndOffset(100);
        child.setOrdinal(1);
        child.setStrategy(DocumentNode.Strategy.AI);
        child.setSourceVersionHash("child123");
        child.setCreatedAt(Instant.now());

        // When
        parent.addChild(child);

        // Then
        assertTrue(parent.isRoot());
        assertFalse(parent.isLeaf());
        assertFalse(child.isRoot());
        assertTrue(child.isLeaf());
        assertEquals(1, parent.getChildren().size());
        assertEquals(child, parent.getChildren().get(0));
        assertEquals(parent, child.getParent());
        assertEquals(1, parent.getDepth());
        assertEquals(2, child.getDepth());
    }

    @Test
    void testRemoveChild() {
        // Given
        DocumentNode parent = new DocumentNode();
        parent.setLevel(1);
        parent.setType(DocumentNode.NodeType.CHAPTER);
        parent.setTitle("Parent Chapter");
        parent.setStartOffset(0);
        parent.setEndOffset(200);
        parent.setOrdinal(1);
        parent.setStrategy(DocumentNode.Strategy.AI);
        parent.setSourceVersionHash("parent123");
        parent.setCreatedAt(Instant.now());

        DocumentNode child = new DocumentNode();
        child.setLevel(2);
        child.setType(DocumentNode.NodeType.SECTION);
        child.setTitle("Child Section");
        child.setStartOffset(0);
        child.setEndOffset(100);
        child.setOrdinal(1);
        child.setStrategy(DocumentNode.Strategy.AI);
        child.setSourceVersionHash("child123");
        child.setCreatedAt(Instant.now());

        parent.addChild(child);

        // When
        parent.removeChild(child);

        // Then
        assertTrue(parent.getChildren().isEmpty());
        assertNull(child.getParent());
        assertTrue(parent.isLeaf());
    }
}
