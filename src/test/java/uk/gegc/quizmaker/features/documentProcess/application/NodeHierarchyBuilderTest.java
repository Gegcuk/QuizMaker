package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import java.math.BigDecimal;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NodeHierarchyBuilderTest {

    @Test
    void shouldReassignParentEndOffsetsBasedOnChildren() {
        // Create a hierarchy where parent end offsets don't properly contain children
        DocumentNode parent = createNode("Parent", 0, 100, 0);
        DocumentNode child1 = createNode("Child1", 10, 50, 1);
        DocumentNode child2 = createNode("Child2", 60, 120, 1); // Extends beyond parent's end
        
        List<DocumentNode> nodes = Arrays.asList(parent, child1, child2);
        
        // Build hierarchy
        NodeHierarchyBuilder builder = new NodeHierarchyBuilder();
        List<DocumentNode> result = builder.buildHierarchy(nodes);
        
        // Verify parent end offset was updated to contain the last child
        DocumentNode updatedParent = result.stream()
                .filter(n -> n.getTitle().equals("Parent"))
                .findFirst()
                .orElseThrow();
        
        assertEquals(120, updatedParent.getEndOffset(), 
                "Parent end offset should be updated to match the last child's end offset");
        assertEquals(0, updatedParent.getStartOffset(), 
                "Parent start offset should remain unchanged");
    }

    @Test
    void shouldHandleNestedHierarchyEndOffsetReassignment() {
        // Create a 3-level hierarchy
        DocumentNode grandparent = createNode("Grandparent", 0, 100, 0);
        DocumentNode parent = createNode("Parent", 10, 80, 1);
        DocumentNode child = createNode("Child", 20, 150, 2); // Extends beyond both parent and grandparent
        
        List<DocumentNode> nodes = Arrays.asList(grandparent, parent, child);
        
        // Build hierarchy
        NodeHierarchyBuilder builder = new NodeHierarchyBuilder();
        List<DocumentNode> result = builder.buildHierarchy(nodes);
        
        // Verify both parent and grandparent end offsets were updated
        DocumentNode updatedParent = result.stream()
                .filter(n -> n.getTitle().equals("Parent"))
                .findFirst()
                .orElseThrow();
        
        DocumentNode updatedGrandparent = result.stream()
                .filter(n -> n.getTitle().equals("Grandparent"))
                .findFirst()
                .orElseThrow();
        
        assertEquals(150, updatedParent.getEndOffset(), 
                "Parent end offset should be updated to match child's end offset");
        assertEquals(150, updatedGrandparent.getEndOffset(), 
                "Grandparent end offset should be updated to match child's end offset");
    }

    @Test
    void shouldNotChangeParentEndOffsetIfAlreadyCorrect() {
        // Create a hierarchy where parent already properly contains children
        DocumentNode parent = createNode("Parent", 0, 120, 0);
        DocumentNode child1 = createNode("Child1", 10, 50, 1);
        DocumentNode child2 = createNode("Child2", 60, 100, 1); // Within parent's bounds
        
        List<DocumentNode> nodes = Arrays.asList(parent, child1, child2);
        
        // Build hierarchy
        NodeHierarchyBuilder builder = new NodeHierarchyBuilder();
        List<DocumentNode> result = builder.buildHierarchy(nodes);
        
        // Verify parent end offset was not changed
        DocumentNode updatedParent = result.stream()
                .filter(n -> n.getTitle().equals("Parent"))
                .findFirst()
                .orElseThrow();
        
        assertEquals(120, updatedParent.getEndOffset(), 
                "Parent end offset should remain unchanged when already correct");
    }

    @Test
    void shouldHandleEmptyNodeList() {
        NodeHierarchyBuilder builder = new NodeHierarchyBuilder();
        List<DocumentNode> result = builder.buildHierarchy(List.of());
        
        assertTrue(result.isEmpty(), "Empty list should remain empty");
    }

    @Test
    void shouldHandleSingleNode() {
        DocumentNode singleNode = createNode("Single", 0, 100, 0);
        List<DocumentNode> nodes = Arrays.asList(singleNode);
        
        NodeHierarchyBuilder builder = new NodeHierarchyBuilder();
        List<DocumentNode> result = builder.buildHierarchy(nodes);
        
        assertEquals(1, result.size(), "Single node should remain unchanged");
        assertNull(result.get(0).getParent(), "Single node should have no parent");
    }

    private DocumentNode createNode(String title, int startOffset, int endOffset, int depth) {
        DocumentNode node = new DocumentNode();
        node.setId(UUID.randomUUID());
        node.setTitle(title);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setDepth((short) depth);
        node.setStartAnchor("Start anchor for " + title);
        node.setEndAnchor("End anchor for " + title);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return node;
    }
}



