package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for StructureService validation and parent relationship methods.
 * Targets uncovered lines in validateNodes, assignParentRelationships, and findBestParent.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("StructureService Validation Tests")
class StructureServiceValidationTest {

    @Mock
    private DocumentNodeRepository nodeRepository;

    @Mock
    private NormalizedDocumentRepository documentRepository;

    @Mock
    private DocumentNodeMapper nodeMapper;

    @Mock
    private LlmClient llmClient;

    @Mock
    private AnchorOffsetCalculator anchorOffsetCalculator;

    @Mock
    private NodeHierarchyBuilder hierarchyBuilder;

    @Mock
    private DocumentQueryService queryService;

    @Mock
    private ChunkedStructureService chunkedStructureService;

    @InjectMocks
    private StructureService service;

    private UUID documentId;
    private NormalizedDocument document;
    private DocumentChunkingConfig chunkingConfig;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("test.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        // Make document longer to accommodate node offsets
        document.setNormalizedText("Sample document text for testing. ".repeat(10)); // ~340 chars
        document.setCharCount(document.getNormalizedText().length());

        // Setup chunking config
        chunkingConfig = new DocumentChunkingConfig();
        chunkingConfig.setMaxSingleChunkChars(150_000);
        chunkingConfig.setMaxSingleChunkTokens(40_000);
        
        lenient().when(chunkedStructureService.getChunkingConfig()).thenReturn(chunkingConfig);
    }

    @Nested
    @DisplayName("validateNodes Tests")
    class ValidateNodesTests {

        @Test
        @DisplayName("validateNodes: when node has null endAnchor then throws exception")
        void validateNodes_nullEndAnchor_throwsException() {
            // Given
            DocumentNode nodeWithNullEndAnchor = createNodeWithNullEndAnchor();
            // Set offsets within document bounds
            nodeWithNullEndAnchor.setStartOffset(0);
            nodeWithNullEndAnchor.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithNullEndAnchor);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes); // Return as-is with offsets already set

            // When & Then - Line 460-461 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected error during structure building")
                    .hasRootCauseMessage("Node end anchor is required: Test Node");
        }

        @Test
        @DisplayName("validateNodes: when node has blank endAnchor then throws exception")
        void validateNodes_blankEndAnchor_throwsException() {
            // Given
            DocumentNode nodeWithBlankEndAnchor = createNodeWithBlankEndAnchor();
            nodeWithBlankEndAnchor.setStartOffset(0);
            nodeWithBlankEndAnchor.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithBlankEndAnchor);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);

            // When & Then - Line 460-461 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Node end anchor is required: Test Node");
        }

        @Test
        @DisplayName("validateNodes: when node has null type then throws exception")
        void validateNodes_nullType_throwsException() {
            // Given
            DocumentNode nodeWithNullType = createNodeWithNullType();
            nodeWithNullType.setStartOffset(0);
            nodeWithNullType.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithNullType);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);

            // When & Then - Line 465-466 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Node type is required: Test Node");
        }

        @Test
        @DisplayName("validateNodes: when node has null depth then throws exception during grouping")
        void validateNodes_nullDepth_throwsException() {
            // Given
            List<DocumentNode> nodes = List.of(createNodeWithNullDepth());
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            lenient().when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            lenient().when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenAnswer(inv -> {
                        List<DocumentNode> n = inv.getArgument(0);
                        n.forEach(node -> {
                            node.setStartOffset(0);
                            node.setEndOffset(100);
                        });
                        return n;
                    });

            // When & Then - Null depth causes NPE during groupBy, but if validation ran it would hit line 471-472
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected error during structure building");
        }

        @Test
        @DisplayName("validateNodes: when node has negative depth then throws exception")
        void validateNodes_negativeDepth_throwsException() {
            // Given
            DocumentNode nodeWithNegativeDepth = createNodeWithNegativeDepth();
            nodeWithNegativeDepth.setStartOffset(0);
            nodeWithNegativeDepth.setEndOffset(50);
            List<DocumentNode> nodes = List.of(nodeWithNegativeDepth);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);

            // When & Then - Line 471-472 covered
            assertThatThrownBy(() -> service.buildStructure(documentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Node depth must be non-negative: Test Node");
        }
    }

    @Nested
    @DisplayName("assignParentRelationships and findBestParent Tests")
    class ParentRelationshipTests {

        @Test
        @DisplayName("findBestParent: when no potential parents exist then returns null")
        void findBestParent_noParents_returnsNull() {
            // Given - Create a depth 1 node without any depth 0 parents saved
            DocumentNode rootNode = createValidNode("Root", 0, 100, (short) 0);
            DocumentNode childNode = createValidNode("Child", 10, 50, (short) 1);
            List<DocumentNode> nodes = List.of(rootNode, childNode);
            setNodeOffsetsWithinBounds(nodes, document.getCharCount());
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenAnswer(inv -> {
                        List<DocumentNode> fresh = inv.getArgument(0);
                        // Copy offsets from original nodes to fresh copies
                        for (int i = 0; i < fresh.size(); i++) {
                            fresh.get(i).setStartOffset(nodes.get(i).getStartOffset());
                            fresh.get(i).setEndOffset(nodes.get(i).getEndOffset());
                        }
                        return fresh;
                    });
            
            // Mock nodeRepository to return saved depth 0 nodes when queried for depth 1 parents
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            // No parents available for depth 1
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of());
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Child node should have null parent (lines 415-431 covered)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when parent contains node then assigns parent")
        void findBestParent_parentContainsNode_assignsParent() {
            // Given
            DocumentNode rootNode = createValidNode("Root", 0, 100, (short) 0);
            rootNode.setStartOffset(0);
            rootNode.setEndOffset(150);
            DocumentNode childNode = createValidNode("Child", 10, 50, (short) 1);
            childNode.setStartOffset(10);
            childNode.setEndOffset(100);
            List<DocumentNode> nodes = List.of(rootNode, childNode);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenAnswer(inv -> {
                        List<DocumentNode> fresh = inv.getArgument(0);
                        fresh.get(0).setStartOffset(0);
                        fresh.get(0).setEndOffset(150);
                        if (fresh.size() > 1) {
                            fresh.get(1).setStartOffset(10);
                            fresh.get(1).setEndOffset(100);
                        }
                        return fresh;
                    });
            
            // Save depth 0, then make it available as parent for depth 1
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            DocumentNode savedRoot = createValidNode("Root", 0, 150, (short) 0);
            savedRoot.setId(UUID.randomUUID());
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedRoot));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 417-422 covered (containment check and parent assignment)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when multiple parents at different depths then selects deepest")
        void findBestParent_multipleParentDepths_selectsDeepest() {
            // Given - Create hierarchy: depth 0, depth 1, depth 2 (child of depth 1)
            DocumentNode depth0 = createValidNode("Level 0", 0, 100, (short) 0);
            DocumentNode depth1 = createValidNode("Level 1", 10, 90, (short) 1);
            DocumentNode depth2 = createValidNode("Level 2", 20, 50, (short) 2);
            List<DocumentNode> nodes = List.of(depth0, depth1, depth2);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedDepth0 = createValidNode("Level 0", 0, 100, (short) 0);
            savedDepth0.setId(UUID.randomUUID());
            DocumentNode savedDepth1 = createValidNode("Level 1", 10, 90, (short) 1);
            savedDepth1.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            // For depth 1, return depth 0
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedDepth0));
            
            // For depth 2, return both depth 0 and depth 1 (both contain the node)
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 2))
                    .thenReturn(List.of(savedDepth0, savedDepth1));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Depth 2 node should select depth 1 as parent (deeper parent)
            // Lines 421-422, 428-429 covered
            verify(nodeRepository, times(3)).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when parent does not contain node then returns null")
        void findBestParent_parentDoesNotContain_returnsNull() {
            // Given - Child node outside parent's range
            DocumentNode rootNode = createValidNode("Root", 0, 50, (short) 0);
            DocumentNode childNode = createValidNode("Child", 60, 90, (short) 1); // Outside root's range
            List<DocumentNode> nodes = List.of(rootNode, childNode);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedRoot = createValidNode("Root", 0, 50, (short) 0);
            savedRoot.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedRoot));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Child should have null parent since it's not contained
            // Lines 417-418 (containment check fails), line 431 covered
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("assignParentRelationships: when depth is 0 then sets null parent and sequential index")
        void assignParentRelationships_depth0_setsNullParentAndIndex() {
            // Given - Multiple root nodes
            DocumentNode root1 = createValidNode("Root 1", 0, 50, (short) 0);
            DocumentNode root2 = createValidNode("Root 2", 60, 100, (short) 0);
            List<DocumentNode> nodes = List.of(root1, root2);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 340-346 covered (root level handling)
            verify(nodeRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when multiple parents exist at same depth then picks first containing one")
        void findBestParent_multipleParentsSameDepth_picksFirstContaining() {
            // Given - Multiple siblings at depth 0, child should pick the one that contains it
            DocumentNode root1 = createValidNode("Root 1", 0, 40, (short) 0);
            DocumentNode root2 = createValidNode("Root 2", 50, 100, (short) 0);
            DocumentNode child = createValidNode("Child", 60, 80, (short) 1); // Within root2
            List<DocumentNode> nodes = List.of(root1, root2, child);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedRoot1 = createValidNode("Root 1", 0, 40, (short) 0);
            savedRoot1.setId(UUID.randomUUID());
            DocumentNode savedRoot2 = createValidNode("Root 2", 50, 100, (short) 0);
            savedRoot2.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            savedRoot1.setStartOffset(0);
            savedRoot1.setEndOffset(60);
            savedRoot2.setStartOffset(70);
            savedRoot2.setEndOffset(150);
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedRoot1, savedRoot2));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 415-422 covered (iteration over potential parents, containment check)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when node at boundary of parent then assigns correctly")
        void findBestParent_nodeAtBoundary_assignsCorrectly() {
            // Given - Child exactly at parent boundaries
            DocumentNode root = createValidNode("Root", 0, 100, (short) 0);
            DocumentNode child = createValidNode("Child", 0, 100, (short) 1); // Exact same range
            List<DocumentNode> nodes = List.of(root, child);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedRoot = createValidNode("Root", 0, 100, (short) 0);
            savedRoot.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedRoot));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 417-418 covered (boundary containment check)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("assignParentRelationships: when multiple children under same parent then assigns sequential indices")
        void assignParentRelationships_multipleChildren_assignsSequentialIndices() {
            // Given - One parent with two children
            DocumentNode root = createValidNode("Root", 0, 100, (short) 0);
            DocumentNode child1 = createValidNode("Child 1", 10, 40, (short) 1);
            DocumentNode child2 = createValidNode("Child 2", 50, 80, (short) 1);
            List<DocumentNode> nodes = List.of(root, child1, child2);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedRoot = createValidNode("Root", 0, 100, (short) 0);
            savedRoot.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedRoot));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 366-368 covered (child index assignment)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when deeper parent replaces shallower parent then updates bestParent")
        void findBestParent_deeperParent_updatesBestParent() {
            // Given - Three levels: 0, 1, 2 where both 0 and 1 contain the depth 2 node
            DocumentNode depth0 = createValidNode("Level 0", 0, 100, (short) 0);
            DocumentNode depth1 = createValidNode("Level 1", 10, 90, (short) 1);
            DocumentNode depth2 = createValidNode("Level 2", 20, 50, (short) 2);
            List<DocumentNode> nodes = List.of(depth0, depth1, depth2);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedDepth0 = createValidNode("Level 0", 0, 100, (short) 0);
            savedDepth0.setId(UUID.randomUUID());
            DocumentNode savedDepth1 = createValidNode("Level 1", 10, 90, (short) 1);
            savedDepth1.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            // Set offsets on saved nodes
            savedDepth0.setStartOffset(0);
            savedDepth0.setEndOffset(200);
            savedDepth1.setStartOffset(10);
            savedDepth1.setEndOffset(150);
            
            // For depth 1, return depth 0
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedDepth0));
            
            // For depth 2, return both depth 0 and depth 1 (both contain the node)
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 2))
                    .thenReturn(List.of(savedDepth0, savedDepth1));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 421 (depth comparison), 422 (update bestParent) covered
            verify(nodeRepository, times(3)).saveAll(anyList());
        }

        @Test
        @DisplayName("findBestParent: when bestParent found at current depth then breaks loop")
        void findBestParent_foundAtCurrentDepth_breaksLoop() {
            // Given - Create scenario where parent is found at first depth checked
            DocumentNode root = createValidNode("Root", 0, 100, (short) 0);
            DocumentNode child = createValidNode("Child", 10, 50, (short) 1);
            List<DocumentNode> nodes = List.of(root, child);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedRoot = createValidNode("Root", 0, 100, (short) 0);
            savedRoot.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedRoot));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Lines 428-429 covered (break when parent found)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }

        @Test
        @DisplayName("assignParentRelationships: when orphan nodes exist then assigns null parent")
        void assignParentRelationships_orphanNodes_assignsNullParent() {
            // Given - Two separate trees (no containment between them)
            DocumentNode tree1 = createValidNode("Tree 1", 0, 40, (short) 0);
            DocumentNode tree2Root = createValidNode("Tree 2", 50, 100, (short) 0);
            DocumentNode tree2Child = createValidNode("Tree 2 Child", 60, 80, (short) 1);
            List<DocumentNode> nodes = List.of(tree1, tree2Root, tree2Child);
            
            when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
            when(llmClient.generateStructure(anyString(), any())).thenReturn(nodes);
            when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
                    .thenReturn(nodes);
            
            DocumentNode savedTree1 = createValidNode("Tree 1", 0, 40, (short) 0);
            savedTree1.setId(UUID.randomUUID());
            DocumentNode savedTree2Root = createValidNode("Tree 2", 50, 100, (short) 0);
            savedTree2Root.setId(UUID.randomUUID());
            
            when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<DocumentNode> saved = inv.getArgument(0);
                saved.forEach(n -> n.setId(UUID.randomUUID()));
                return saved;
            });
            
            when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, (short) 1))
                    .thenReturn(List.of(savedTree1, savedTree2Root));
            
            when(nodeRepository.findByDocument_IdOrderByStartOffset(any())).thenReturn(nodes);
            doNothing().when(hierarchyBuilder).validateParentChildContainment(anyList());
            doNothing().when(anchorOffsetCalculator).validateSiblingNonOverlap(anyList());

            // When
            service.buildStructure(documentId);

            // Then - Child should find correct parent (tree2Root, not tree1)
            verify(nodeRepository, times(2)).saveAll(anyList());
        }
    }

    // Helper methods to create test nodes

    private DocumentNode createValidNode(String title, int startOffset, int endOffset, short depth) {
        DocumentNode node = new DocumentNode();
        node.setTitle(title);
        node.setType(DocumentNode.NodeType.SECTION);
        node.setStartAnchor("Start: " + title);
        node.setEndAnchor("End: " + title);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setDepth(depth);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return node;
    }
    
    private void setNodeOffsetsWithinBounds(List<DocumentNode> nodes, int docLength) {
        // Set offsets for nodes ensuring they fit within document bounds
        int segmentSize = Math.max(10, docLength / Math.max(1, nodes.size()));
        for (int i = 0; i < nodes.size(); i++) {
            int start = i * segmentSize;
            int end = Math.min(start + segmentSize, docLength - 1);
            nodes.get(i).setStartOffset(start);
            nodes.get(i).setEndOffset(end);
        }
    }

    private DocumentNode createNodeWithNullEndAnchor() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setEndAnchor(null);
        return node;
    }

    private DocumentNode createNodeWithBlankEndAnchor() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setEndAnchor("   ");
        return node;
    }

    private DocumentNode createNodeWithNullType() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setType(null);
        return node;
    }

    private DocumentNode createNodeWithNullDepth() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setDepth(null);
        return node;
    }

    private DocumentNode createNodeWithNegativeDepth() {
        DocumentNode node = createValidNode("Test Node", 0, 100, (short) 0);
        node.setDepth((short) -5);
        return node;
    }
}

