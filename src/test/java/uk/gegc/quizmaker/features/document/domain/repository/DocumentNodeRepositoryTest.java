package uk.gegc.quizmaker.features.document.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DocumentNodeRepositoryTest {

    @Autowired
    private DocumentNodeRepository nodeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User user;
    private Document document1;
    private Document document2;
    private DocumentNode rootNode1;
    private DocumentNode rootNode2;
    private DocumentNode childNode1;
    private DocumentNode childNode2;
    private DocumentNode overlappingNode1;
    private DocumentNode overlappingNode2;

    @BeforeEach
    void setUp() {
        // Create test user
        user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("password");
        entityManager.persistAndFlush(user);

        // Create test documents
        document1 = new Document();
        document1.setOriginalFilename("test1.pdf");
        document1.setContentType("application/pdf");
        document1.setFileSize(1024L);
        document1.setFilePath("/uploads/test1.pdf");
        document1.setStatus(Document.DocumentStatus.PROCESSED);
        document1.setUploadedBy(user);
        document1.setUploadedAt(LocalDateTime.now());
        entityManager.persistAndFlush(document1);

        document2 = new Document();
        document2.setOriginalFilename("test2.pdf");
        document2.setContentType("application/pdf");
        document2.setFileSize(2048L);
        document2.setFilePath("/uploads/test2.pdf");
        document2.setStatus(Document.DocumentStatus.PROCESSED);
        document2.setUploadedBy(user);
        document2.setUploadedAt(LocalDateTime.now());
        entityManager.persistAndFlush(document2);

        // Create test nodes
        rootNode1 = createNode(document1, null, "Chapter 1", 0, 0, 100, DocumentNode.NodeType.CHAPTER, 1);
        rootNode2 = createNode(document1, null, "Chapter 2", 1, 150, 300, DocumentNode.NodeType.CHAPTER, 2);
        childNode1 = createNode(document1, rootNode1, "Section 1.1", 0, 10, 50, DocumentNode.NodeType.SECTION, 2);
        childNode2 = createNode(document1, rootNode1, "Section 1.2", 1, 60, 90, DocumentNode.NodeType.SECTION, 2);
        
        // Create overlapping nodes for testing (these should be the only ones overlapping with range [220, 250)
        overlappingNode1 = createNode(document1, null, "Overlapping 1", 3, 200, 250, DocumentNode.NodeType.PARAGRAPH, 1);
        overlappingNode2 = createNode(document1, null, "Overlapping 2", 4, 220, 280, DocumentNode.NodeType.PARAGRAPH, 1);

        entityManager.flush();
    }

    private DocumentNode createNode(Document document, DocumentNode parent, String title, int ordinal, 
                                  int startOffset, int endOffset, DocumentNode.NodeType type, int level) {
        DocumentNode node = new DocumentNode();
        node.setDocument(document);
        node.setParent(parent);
        node.setTitle(title);
        node.setOrdinal(ordinal);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setType(type);
        node.setLevel(level);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setSourceVersionHash("test-hash");
        return entityManager.persistAndFlush(node);
    }

    @Test
    void findByDocumentIdOrderByStartOffset_returnsGlobalOrdering() {
        // When
        List<DocumentNode> nodes = nodeRepository.findByDocumentIdOrderByStartOffset(document1.getId());

        // Then
        assertThat(nodes).hasSize(6);
        assertThat(nodes).extracting("startOffset")
                .containsExactly(0, 10, 60, 150, 200, 220);
        assertThat(nodes).extracting("title")
                .containsExactly("Chapter 1", "Section 1.1", "Section 1.2", "Chapter 2", "Overlapping 1", "Overlapping 2");
    }

    @Test
    void findOverlapping_returnsNodesIntersectingStartEnd() {
        // Given - search for nodes overlapping with range [220, 250)
        int startOffset = 220;
        int endOffset = 250;

        // When
        List<DocumentNode> overlappingNodes = nodeRepository.findOverlapping(document1.getId(), startOffset, endOffset);

        // Then
        assertThat(overlappingNodes).hasSize(3); // Chapter 2 (150-300), Overlapping 1 (200-250), Overlapping 2 (220-280)
        assertThat(overlappingNodes).extracting("title")
                .containsExactlyInAnyOrder("Chapter 2", "Overlapping 1", "Overlapping 2");
        
        // Verify the overlap logic: startOffset < endOffset AND endOffset > startOffset
        assertThat(overlappingNodes).allMatch(node -> 
                node.getStartOffset() < endOffset && node.getEndOffset() > startOffset);
    }

    @Test
    void findOverlapping_withNoOverlap_returnsEmptyList() {
        // Given - search for nodes overlapping with range [400, 500) (no nodes in this range)
        int startOffset = 400;
        int endOffset = 500;

        // When
        List<DocumentNode> overlappingNodes = nodeRepository.findOverlapping(document1.getId(), startOffset, endOffset);

        // Then
        assertThat(overlappingNodes).isEmpty();
    }

    @Test
    void findByDocument_IdAndParent_IdOrderByOrdinalAsc_returnsSiblingOrder() {
        // When
        List<DocumentNode> siblings = nodeRepository.findByDocument_IdAndParent_IdOrderByOrdinalAsc(
                document1.getId(), rootNode1.getId());

        // Then
        assertThat(siblings).hasSize(2);
        assertThat(siblings).extracting("ordinal")
                .containsExactly(0, 1); // childNode1 has ordinal 0, childNode2 has ordinal 1
        assertThat(siblings).extracting("title")
                .containsExactly("Section 1.1", "Section 1.2");
    }

    @Test
    void findByDocument_IdAndParentIsNullOrderByOrdinalAsc_returnsRootNodesInOrder() {
        // When
        List<DocumentNode> rootNodes = nodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(document1.getId());

        // Then
        assertThat(rootNodes).hasSize(4); // rootNode1, rootNode2, overlappingNode1, overlappingNode2
        assertThat(rootNodes).extracting("ordinal")
                .containsExactly(0, 1, 3, 4); // Ordered by ordinal ascending
        assertThat(rootNodes).allMatch(node -> node.getParent() == null);
    }

    @Test
    void deleteByDocument_Id_removesAllNodesForDoc() {
        // Given
        assertThat(nodeRepository.countByDocument_Id(document1.getId())).isEqualTo(6);
        assertThat(nodeRepository.countByDocument_Id(document2.getId())).isEqualTo(0);

        // When
        nodeRepository.deleteByDocument_Id(document1.getId());

        // Then
        assertThat(nodeRepository.countByDocument_Id(document1.getId())).isEqualTo(0);
        assertThat(nodeRepository.existsByDocument_Id(document1.getId())).isFalse();
    }

    @Test
    void findBySourceVersionHash_returnsNodesWithMatchingHash() {
        // When
        List<DocumentNode> nodesWithHash = nodeRepository.findBySourceVersionHash("test-hash");

        // Then
        assertThat(nodesWithHash).hasSize(6);
        assertThat(nodesWithHash).allMatch(node -> "test-hash".equals(node.getSourceVersionHash()));
    }

    @Test
    void existsByDocument_Id_returnsTrueWhenNodesExist() {
        // When
        boolean exists = nodeRepository.existsByDocument_Id(document1.getId());

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    void existsByDocument_Id_returnsFalseWhenNoNodesExist() {
        // When
        boolean exists = nodeRepository.existsByDocument_Id(document2.getId());

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    void countByDocument_Id_returnsCorrectCount() {
        // When
        long count = nodeRepository.countByDocument_Id(document1.getId());

        // Then
        assertThat(count).isEqualTo(6);
    }

    @Test
    void findByDocumentIdAndTypeOrderByStartOffset_returnsNodesOfSpecificType() {
        // When
        List<DocumentNode> chapterNodes = nodeRepository.findByDocumentIdAndTypeOrderByStartOffset(
                document1.getId(), DocumentNode.NodeType.CHAPTER);

        // Then
        assertThat(chapterNodes).hasSize(2);
        assertThat(chapterNodes).allMatch(node -> node.getType() == DocumentNode.NodeType.CHAPTER);
        assertThat(chapterNodes).extracting("startOffset")
                .containsExactly(0, 150); // Ordered by startOffset
    }

    @Test
    void findNextOrdinalForParent_returnsCorrectNextOrdinal() {
        // When
        Integer nextOrdinal = nodeRepository.findNextOrdinalForParent(document1.getId(), rootNode1.getId());

        // Then
        assertThat(nextOrdinal).isEqualTo(2); // childNode1 has ordinal 0, childNode2 has ordinal 1, so next is 2
    }

    @Test
    void nextRootOrdinal_returnsCorrectNextOrdinal() {
        // When
        Integer nextOrdinal = nodeRepository.nextRootOrdinal(document1.getId());

        // Then
        assertThat(nextOrdinal).isEqualTo(5); // root nodes have ordinals 0, 1, 3, 4, so next is 5
    }

    @Test
    void findOverlapping_withExactBoundary_returnsOverlappingNodes() {
        // Given - search for nodes overlapping with exact boundary of overlappingNode1
        int startOffset = 200;
        int endOffset = 250;

        // When
        List<DocumentNode> overlappingNodes = nodeRepository.findOverlapping(document1.getId(), startOffset, endOffset);

        // Then
        assertThat(overlappingNodes).hasSize(3); // Chapter 2 (150-300), Overlapping 1 (200-250), Overlapping 2 (220-280)
        assertThat(overlappingNodes).extracting("title")
                .containsExactlyInAnyOrder("Chapter 2", "Overlapping 1", "Overlapping 2");
    }

    @Test
    void findOverlapping_withContainedRange_returnsContainingNodes() {
        // Given - search for nodes that contain the range [225, 235)
        int startOffset = 225;
        int endOffset = 235;

        // When
        List<DocumentNode> overlappingNodes = nodeRepository.findOverlapping(document1.getId(), startOffset, endOffset);

        // Then
        assertThat(overlappingNodes).hasSize(3); // Chapter 2 (150-300), Overlapping 1 (200-250), Overlapping 2 (220-280)
        assertThat(overlappingNodes).extracting("title")
                .containsExactlyInAnyOrder("Chapter 2", "Overlapping 1", "Overlapping 2");
    }
}
