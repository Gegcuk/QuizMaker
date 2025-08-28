package uk.gegc.quizmaker.features.documentProcess.infra.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.documentProcess.api.dto.FlatNode;
import uk.gegc.quizmaker.features.documentProcess.api.dto.NodeView;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentNodeMapper Tests")
class DocumentNodeMapperTest {

    private DocumentNodeMapper mapper;
    private NormalizedDocument document;
    private DocumentNode rootNode;
    private DocumentNode childNode1;
    private DocumentNode childNode2;

    @BeforeEach
    void setUp() {
        mapper = new DocumentNodeMapper();
        
        document = new NormalizedDocument();
        document.setId(UUID.randomUUID());
        
        rootNode = createNode(UUID.randomUUID(), document, null, 1, DocumentNode.NodeType.CHAPTER, "Chapter 1", 0, 100, (short) 0, new BigDecimal("0.95"), "{\"level\":1}");
        childNode1 = createNode(UUID.randomUUID(), document, rootNode, 1, DocumentNode.NodeType.SECTION, "Section 1.1", 10, 50, (short) 1, new BigDecimal("0.90"), "{\"level\":2}");
        childNode2 = createNode(UUID.randomUUID(), document, rootNode, 2, DocumentNode.NodeType.SECTION, "Section 1.2", 51, 90, (short) 1, new BigDecimal("0.85"), "{\"level\":2}");
    }

    @Test
    @DisplayName("toFlatNode_mapsAllFields")
    void toFlatNode_mapsAllFields() {
        // When
        FlatNode result = mapper.toFlatNode(rootNode);

        // Then
        assertThat(result.id()).isEqualTo(rootNode.getId());
        assertThat(result.documentId()).isEqualTo(document.getId());
        assertThat(result.parentId()).isNull();
        assertThat(result.idx()).isEqualTo(1);
        assertThat(result.type()).isEqualTo(DocumentNode.NodeType.CHAPTER);
        assertThat(result.title()).isEqualTo("Chapter 1");
        assertThat(result.startOffset()).isEqualTo(0);
        assertThat(result.endOffset()).isEqualTo(100);
        assertThat(result.depth()).isEqualTo((short) 0);
        assertThat(result.aiConfidence()).isEqualTo(new BigDecimal("0.95"));
        assertThat(result.metaJson()).isEqualTo("{\"level\":1}");
    }

    @Test
    @DisplayName("toFlatNode_withParent_mapsParentId")
    void toFlatNode_withParent_mapsParentId() {
        // When
        FlatNode result = mapper.toFlatNode(childNode1);

        // Then
        assertThat(result.parentId()).isEqualTo(rootNode.getId());
        assertThat(result.documentId()).isEqualTo(document.getId());
    }

    @Test
    @DisplayName("toNodeView_mapsWithoutChildren")
    void toNodeView_mapsWithoutChildren() {
        // When
        NodeView result = mapper.toNodeView(rootNode);

        // Then
        assertThat(result.id()).isEqualTo(rootNode.getId());
        assertThat(result.documentId()).isEqualTo(document.getId());
        assertThat(result.parentId()).isNull();
        assertThat(result.idx()).isEqualTo(1);
        assertThat(result.type()).isEqualTo(DocumentNode.NodeType.CHAPTER);
        assertThat(result.title()).isEqualTo("Chapter 1");
        assertThat(result.startOffset()).isEqualTo(0);
        assertThat(result.endOffset()).isEqualTo(100);
        assertThat(result.depth()).isEqualTo((short) 0);
        assertThat(result.aiConfidence()).isEqualTo(new BigDecimal("0.95"));
        assertThat(result.metaJson()).isEqualTo("{\"level\":1}");
        assertThat(result.children()).isEmpty(); // Children should be empty by design
    }

    @Test
    @DisplayName("buildTree_buildsNestedHierarchy")
    void buildTree_buildsNestedHierarchy() {
        // Given
        List<DocumentNode> nodes = List.of(rootNode, childNode1, childNode2);

        // When
        List<NodeView> result = mapper.buildTree(nodes);

        // Then
        assertThat(result).hasSize(1); // One root node
        NodeView root = result.get(0);
        assertThat(root.id()).isEqualTo(rootNode.getId());
        assertThat(root.children()).hasSize(2);
        assertThat(root.children().get(0).id()).isEqualTo(childNode1.getId());
        assertThat(root.children().get(1).id()).isEqualTo(childNode2.getId());
    }

    @Test
    @DisplayName("buildTree_preservesSiblingOrderByIdx")
    void buildTree_preservesSiblingOrderByIdx() {
        // Given
        List<DocumentNode> nodes = List.of(rootNode, childNode2, childNode1); // Wrong order

        // When
        List<NodeView> result = mapper.buildTree(nodes);

        // Then
        assertThat(result).hasSize(1);
        NodeView root = result.get(0);
        assertThat(root.children()).hasSize(2);
        // Should be ordered by idx, not by input order
        assertThat(root.children().get(0).idx()).isEqualTo(1); // childNode1
        assertThat(root.children().get(1).idx()).isEqualTo(2); // childNode2
    }

    @Test
    @DisplayName("buildTree_handlesMultipleRoots")
    void buildTree_handlesMultipleRoots() {
        // Given
        DocumentNode root2 = createNode(UUID.randomUUID(), document, null, 2, DocumentNode.NodeType.CHAPTER, "Chapter 2", 101, 200, (short) 0, null, null);
        List<DocumentNode> nodes = List.of(rootNode, root2, childNode1, childNode2);

        // When
        List<NodeView> result = mapper.buildTree(nodes);

        // Then
        assertThat(result).hasSize(2); // Two root nodes
        assertThat(result.get(0).idx()).isEqualTo(1); // rootNode
        assertThat(result.get(1).idx()).isEqualTo(2); // root2
        assertThat(result.get(0).children()).hasSize(2); // rootNode has children
        assertThat(result.get(1).children()).isEmpty(); // root2 has no children
    }

    @Test
    @DisplayName("buildTree_emptyInput_returnsEmptyList")
    void buildTree_emptyInput_returnsEmptyList() {
        // When
        List<NodeView> result = mapper.buildTree(List.of());

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("toFlatNodeList_mapsAllNodes")
    void toFlatNodeList_mapsAllNodes() {
        // Given
        List<DocumentNode> nodes = List.of(rootNode, childNode1, childNode2);

        // When
        List<FlatNode> result = mapper.toFlatNodeList(nodes);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).id()).isEqualTo(rootNode.getId());
        assertThat(result.get(1).id()).isEqualTo(childNode1.getId());
        assertThat(result.get(2).id()).isEqualTo(childNode2.getId());
    }

    @Test
    @DisplayName("toNodeViewList_mapsAllNodes")
    void toNodeViewList_mapsAllNodes() {
        // Given
        List<DocumentNode> nodes = List.of(rootNode, childNode1, childNode2);

        // When
        List<NodeView> result = mapper.toNodeViewList(nodes);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).id()).isEqualTo(rootNode.getId());
        assertThat(result.get(1).id()).isEqualTo(childNode1.getId());
        assertThat(result.get(2).id()).isEqualTo(childNode2.getId());
    }

    private DocumentNode createNode(UUID id, NormalizedDocument document, DocumentNode parent, int idx, 
                                   DocumentNode.NodeType type, String title, int startOffset, int endOffset, 
                                   short depth, BigDecimal aiConfidence, String metaJson) {
        DocumentNode node = new DocumentNode();
        node.setId(id);
        node.setDocument(document);
        node.setParent(parent);
        node.setIdx(idx);
        node.setType(type);
        node.setTitle(title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setDepth(depth);
        node.setAiConfidence(aiConfidence);
        node.setMetaJson(metaJson);
        return node;
    }
}
