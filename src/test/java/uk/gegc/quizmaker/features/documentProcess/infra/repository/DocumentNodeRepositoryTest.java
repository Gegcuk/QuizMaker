package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
@DisplayName("DocumentNodeRepository Tests")
class DocumentNodeRepositoryTest {

    @Autowired
    private DocumentNodeRepository repository;

    @Autowired
    private NormalizedDocumentRepository documentRepository;

    private NormalizedDocument document1;
    private NormalizedDocument document2;
    private DocumentNode root1;
    private DocumentNode root2;
    private DocumentNode child1;
    private DocumentNode child2;

    @BeforeEach
    void setUp() {
        // Create test documents
        document1 = new NormalizedDocument();
        document1.setOriginalName("test1.txt");
        document1.setSource(NormalizedDocument.DocumentSource.TEXT);
        document1.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document1 = documentRepository.save(document1);

        document2 = new NormalizedDocument();
        document2.setOriginalName("test2.txt");
        document2.setSource(NormalizedDocument.DocumentSource.TEXT);
        document2.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document2 = documentRepository.save(document2);

        // Create test nodes
        root1 = createNode(document1, null, 1, DocumentNode.NodeType.CHAPTER, "Chapter 1", 0, 100, (short) 0);
        root2 = createNode(document1, null, 2, DocumentNode.NodeType.CHAPTER, "Chapter 2", 101, 200, (short) 0);
        child1 = createNode(document1, root1, 1, DocumentNode.NodeType.SECTION, "Section 1.1", 10, 50, (short) 1);
        child2 = createNode(document1, root1, 2, DocumentNode.NodeType.SECTION, "Section 1.2", 51, 90, (short) 1);

        repository.saveAll(List.of(root1, root2, child1, child2));
    }

    @Test
    @DisplayName("findRootNodesByDocumentId_ordersByIdx")
    void findRootNodesByDocumentId_ordersByIdx() {
        // When
        List<DocumentNode> roots = repository.findRootNodesByDocumentId(document1.getId());

        // Then
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).getIdx()).isEqualTo(1);
        assertThat(roots.get(0).getTitle()).isEqualTo("Chapter 1");
        assertThat(roots.get(1).getIdx()).isEqualTo(2);
        assertThat(roots.get(1).getTitle()).isEqualTo("Chapter 2");
    }

    @Test
    @DisplayName("findAllByDocumentIdOrderByStartOffset_ordersAscending")
    void findAllByDocumentIdOrderByStartOffset_ordersAscending() {
        // When
        List<DocumentNode> nodes = repository.findAllByDocumentIdOrderByStartOffset(document1.getId());

        // Then
        assertThat(nodes).hasSize(4);
        assertThat(nodes.get(0).getStartOffset()).isEqualTo(0);   // root1
        assertThat(nodes.get(1).getStartOffset()).isEqualTo(10);  // child1
        assertThat(nodes.get(2).getStartOffset()).isEqualTo(51);  // child2
        assertThat(nodes.get(3).getStartOffset()).isEqualTo(101); // root2
    }

    @Test
    @DisplayName("findByDocumentIdAndNodeId_returnsExactNode")
    void findByDocumentIdAndNodeId_returnsExactNode() {
        // When
        Optional<DocumentNode> found = repository.findByDocumentIdAndNodeId(document1.getId(), child1.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(child1.getId());
        assertThat(found.get().getTitle()).isEqualTo("Section 1.1");
    }

    @Test
    @DisplayName("findByDocumentIdAndNodeId_wrongDocument_returnsEmpty")
    void findByDocumentIdAndNodeId_wrongDocument_returnsEmpty() {
        // When
        Optional<DocumentNode> found = repository.findByDocumentIdAndNodeId(document2.getId(), child1.getId());

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findAllForTree_ordersRootsThenChildrenByIdx")
    void findAllForTree_ordersRootsThenChildrenByIdx() {
        // When
        List<DocumentNode> nodes = repository.findAllForTree(document1.getId());

        // Then
        assertThat(nodes).hasSize(4);
        // Roots should come first (null parent), ordered by idx
        assertThat(nodes.get(0).getParent()).isNull();
        assertThat(nodes.get(0).getIdx()).isEqualTo(1);
        assertThat(nodes.get(1).getParent()).isNull();
        assertThat(nodes.get(1).getIdx()).isEqualTo(2);
        // Children should come after, ordered by parent then idx
        assertThat(nodes.get(2).getParent().getId()).isEqualTo(root1.getId());
        assertThat(nodes.get(2).getIdx()).isEqualTo(1);
        assertThat(nodes.get(3).getParent().getId()).isEqualTo(root1.getId());
        assertThat(nodes.get(3).getIdx()).isEqualTo(2);
    }

    @Test
    @DisplayName("countByDocumentId_returnsTotalCount")
    void countByDocumentId_returnsTotalCount() {
        // When
        long count = repository.countByDocumentId(document1.getId());

        // Then
        assertThat(count).isEqualTo(4);
    }

    @Test
    @DisplayName("deleteByDocument_Id_removesOnlyThatDocsNodes")
    @Transactional
    void deleteByDocument_Id_removesOnlyThatDocsNodes() {
        // Given
        assertThat(repository.countByDocumentId(document1.getId())).isEqualTo(4);
        assertThat(repository.countByDocumentId(document2.getId())).isEqualTo(0);

        // When
        repository.deleteByDocument_Id(document1.getId());

        // Then
        assertThat(repository.countByDocumentId(document1.getId())).isEqualTo(0);
        // Document2 should still exist
        assertThat(documentRepository.findById(document2.getId())).isPresent();
    }

    private DocumentNode createNode(NormalizedDocument document, DocumentNode parent, int idx, 
                                   DocumentNode.NodeType type, String title, int startOffset, int endOffset, short depth) {
        DocumentNode node = new DocumentNode();
        node.setDocument(document);
        node.setParent(parent);
        node.setIdx(idx);
        node.setType(type);
        node.setTitle(title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setDepth(depth);
        return node;
    }
}
