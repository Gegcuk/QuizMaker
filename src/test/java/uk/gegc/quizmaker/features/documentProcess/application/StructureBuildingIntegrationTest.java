package uk.gegc.quizmaker.features.documentProcess.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Structure Building Integration Tests")
class StructureBuildingIntegrationTest {

    @Mock
    private DocumentNodeRepository nodeRepository;

    @Mock
    private NormalizedDocumentRepository documentRepository;

    @Mock
    private OpenAiLlmClient llmClient;

    @Mock
    private AnchorOffsetCalculator anchorOffsetCalculator;

    @Mock
    private NodeHierarchyBuilder hierarchyBuilder;

    @InjectMocks
    private StructureService service;

    private UUID documentId;
    private NormalizedDocument document;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("test-document.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setNormalizedText(createTestDocumentText());
        document.setCharCount(createTestDocumentText().length());
        
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should build structure with single root node")
    void shouldBuildStructureWithSingleRootNode() {
        // Given
        List<DocumentNode> aiNodes = createSingleRootNode();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(1)).saveAll(anyList());
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should build structure with multiple depth levels")
    void shouldBuildStructureWithMultipleDepthLevels() {
        // Given
        List<DocumentNode> aiNodes = createMultiLevelStructure();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(3)).saveAll(anyList()); // One for each depth level
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should handle nodes with missing anchors")
    void shouldHandleNodesWithMissingAnchors() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithMissingAnchors();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString()))
            .thenThrow(new RuntimeException("Anchor not found"));

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");
    }

    @Test
    @DisplayName("Should handle nodes with invalid offsets")
    void shouldHandleNodesWithInvalidOffsets() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithInvalidOffsets();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected error during structure building");
    }

    @Test
    @DisplayName("Should handle empty node list")
    void shouldHandleEmptyNodeList() {
        // Given
        List<DocumentNode> aiNodes = Collections.emptyList();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);

        // When & Then
        assertThatThrownBy(() -> service.buildStructure(documentId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Document not found or no nodes generated by AI");
    }

    @Test
    @DisplayName("Should handle nodes with all node types")
    void shouldHandleNodesWithAllNodeTypes() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithAllTypes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(4)).saveAll(anyList()); // One for each depth level
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should handle nodes with overlapping content")
    void shouldHandleNodesWithOverlappingContent() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithOverlappingContent();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(2)).saveAll(anyList());
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should handle nodes with very long titles and anchors")
    void shouldHandleNodesWithVeryLongTitlesAndAnchors() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithLongContent();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(1)).saveAll(anyList());
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should handle nodes with special characters in titles")
    void shouldHandleNodesWithSpecialCharactersInTitles() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithSpecialCharacters();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(1)).saveAll(anyList());
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should handle nodes with null confidence values")
    void shouldHandleNodesWithNullConfidenceValues() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithNullConfidence();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(1)).saveAll(anyList());
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    @Test
    @DisplayName("Should handle nodes with meta JSON data")
    void shouldHandleNodesWithMetaJsonData() {
        // Given
        List<DocumentNode> aiNodes = createNodesWithMetaJson();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(1)).saveAll(anyList());
        verify(nodeRepository, times(2)).findByDocument_IdOrderByStartOffset(documentId); // Called in processNodesByLevel and performGlobalValidation
    }

    // Helper methods to create test data

    private String createTestDocumentText() {
        return """
            Chapter 1 Building Blocks OCP EXAM OBJECTIVES COVERED IN THIS CHAPTER: Handling 
            date, time, text, numeric and boolean values Use primitives and wrapper classes including Math 
            API, parentheses, type promotion, and casting to evaluate arithmetic and boolean expressions
            
            OCP EXAM OBJECTIVES COVERED IN THIS CHAPTER: Handling 
            date, time, text, numeric and boolean values Use primitives and wrapper classes including Math 
            API, parentheses, type promotion, and casting to evaluate arithmetic and boolean expressions 
            Utilizing Java Object-Oriented Approach Declare and instantiate Java objects including nested class 
            objects, and explain the object life-cycle including creation, reassigning references, and garbage 
            collection Understand variable scopes, use local variable type inference, apply encapsulation, and 
            make objects immutable Welcome to the beginning of your journey to achieve a Java 17 
            certification.
            
            Learning about the Environment The Java environment consists 
            of understanding a number of technologies. In the following sections, we go over the key terms and 
            acronyms you need to know and then discuss what software you need to study for the exam.
            
            Major Components of Java The Java Development Kit (JDK) contains the minimum software you need to 
            do Java development. Key commands include: javac: Converts .java source files into .class bytecode 
            java: Executes the program jar: Packages files together javadoc: Generates documentation
            
            Understanding the Class Structure In Java 
            programs, classes are the basic building blocks. When defining a class, you describe all the parts and 
            characteristics of one of those building blocks.
            
            Fields and Methods Java classes have two primary elements: methods, 
            often called functions or procedures in other languages, and fields, more generally known as 
            variables.
            """;
    }

    private List<DocumentNode> createSingleRootNode() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Chapter 1 Building Blocks");
        node.setStartAnchor("Chapter 1 Building Blocks OCP EXAM OBJECTIVES COVE");
        node.setEndAnchor("extra whitespace doesn't matter in Java syntax. Th");
        node.setStartOffset(0);
        node.setEndOffset(1000); // Valid offset within document length
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(1.0));
        return List.of(node);
    }

    private List<DocumentNode> createMultiLevelStructure() {
        List<DocumentNode> nodes = new ArrayList<>();
        
        // Root level
        DocumentNode chapter = new DocumentNode();
        chapter.setType(DocumentNode.NodeType.CHAPTER);
        chapter.setTitle("Chapter 1 Building Blocks");
        chapter.setStartAnchor("Chapter 1 Building Blocks");
        chapter.setEndAnchor("end of chapter");
        chapter.setStartOffset(0);
        chapter.setEndOffset(1000);
        chapter.setDepth((short) 1);
        chapter.setAiConfidence(BigDecimal.valueOf(1.0));
        nodes.add(chapter);
        
        // Second level
        DocumentNode section = new DocumentNode();
        section.setType(DocumentNode.NodeType.SECTION);
        section.setTitle("OCP EXAM OBJECTIVES");
        section.setStartAnchor("OCP EXAM OBJECTIVES");
        section.setEndAnchor("end of objectives");
        section.setStartOffset(50);
        section.setEndOffset(300);
        section.setDepth((short) 2);
        section.setAiConfidence(BigDecimal.valueOf(0.95));
        nodes.add(section);
        
        // Third level
        DocumentNode subsection = new DocumentNode();
        subsection.setType(DocumentNode.NodeType.SUBSECTION);
        subsection.setTitle("Major Components");
        subsection.setStartAnchor("Major Components");
        subsection.setEndAnchor("end of components");
        subsection.setStartOffset(100);
        subsection.setEndOffset(200);
        subsection.setDepth((short) 3);
        subsection.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(subsection);
        
        return nodes;
    }

    private List<DocumentNode> createNodesWithMissingAnchors() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Chapter with Missing Anchors");
        node.setStartAnchor(null);
        node.setEndAnchor("");
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.8));
        return List.of(node);
    }

    private List<DocumentNode> createNodesWithInvalidOffsets() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Chapter with Invalid Offsets");
        node.setStartAnchor("Chapter");
        node.setEndAnchor("end");
        node.setStartOffset(500);  // Valid start offset
        node.setEndOffset(300);    // Invalid: end before start
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.8));
        return List.of(node);
    }

    private List<DocumentNode> createNodesWithAllTypes() {
        List<DocumentNode> nodes = new ArrayList<>();
        
        for (DocumentNode.NodeType type : DocumentNode.NodeType.values()) {
            DocumentNode node = new DocumentNode();
            node.setType(type);
            node.setTitle(type.name() + " Node");
            node.setStartAnchor(type.name() + " start");
            node.setEndAnchor(type.name() + " end");
            node.setStartOffset(nodes.size() * 100);
            node.setEndOffset(nodes.size() * 100 + 50);
            node.setDepth((short) (nodes.size() % 4));
            node.setAiConfidence(BigDecimal.valueOf(0.9));
            nodes.add(node);
        }
        
        return nodes;
    }

    private List<DocumentNode> createNodesWithOverlappingContent() {
        List<DocumentNode> nodes = new ArrayList<>();
        
        // First node
        DocumentNode node1 = new DocumentNode();
        node1.setType(DocumentNode.NodeType.CHAPTER);
        node1.setTitle("Overlapping Chapter");
        node1.setStartAnchor("Chapter start");
        node1.setEndAnchor("Chapter end");
        node1.setStartOffset(0);
        node1.setEndOffset(200);
        node1.setDepth((short) 1);
        node1.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(node1);
        
        // Second node with overlapping content
        DocumentNode node2 = new DocumentNode();
        node2.setType(DocumentNode.NodeType.SECTION);
        node2.setTitle("Overlapping Section");
        node2.setStartAnchor("Section start");
        node2.setEndAnchor("Section end");
        node2.setStartOffset(100); // Overlaps with first node
        node2.setEndOffset(300);
        node2.setDepth((short) 2);
        node2.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(node2);
        
        return nodes;
    }

    private List<DocumentNode> createNodesWithLongContent() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("A".repeat(512)); // Max title length
        node.setStartAnchor("A".repeat(1000)); // Max anchor length
        node.setEndAnchor("B".repeat(1000));
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return List.of(node);
    }

    private List<DocumentNode> createNodesWithSpecialCharacters() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Chapter with Special Chars: !@#$%^&*()_+-=[]{}|;':\",./<>?");
        node.setStartAnchor("Special chars: !@#$%^&*()");
        node.setEndAnchor("More special chars: []{}|;':\",./<>?");
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        return List.of(node);
    }

    private List<DocumentNode> createNodesWithNullConfidence() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Chapter with Null Confidence");
        node.setStartAnchor("Chapter start");
        node.setEndAnchor("Chapter end");
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setDepth((short) 1);
        node.setAiConfidence(null);
        return List.of(node);
    }

    private List<DocumentNode> createNodesWithMetaJson() {
        DocumentNode node = new DocumentNode();
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setTitle("Chapter with Meta JSON");
        node.setStartAnchor("Chapter start");
        node.setEndAnchor("Chapter end");
        node.setStartOffset(0);
        node.setEndOffset(100);
        node.setDepth((short) 1);
        node.setAiConfidence(BigDecimal.valueOf(0.9));
        node.setMetaJson("{\"key\": \"value\", \"number\": 42, \"array\": [1, 2, 3]}");
        return List.of(node);
    }
}
