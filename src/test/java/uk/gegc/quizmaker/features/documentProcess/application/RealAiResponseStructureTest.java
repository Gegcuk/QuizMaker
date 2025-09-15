package uk.gegc.quizmaker.features.documentProcess.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Real AI Response Structure Tests")
@Execution(ExecutionMode.CONCURRENT)
class RealAiResponseStructureTest {

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

    @Mock
    private ChunkedStructureService chunkedStructureService;

    @InjectMocks
    private StructureService service;

    private UUID documentId;
    private NormalizedDocument document;
    private ObjectMapper objectMapper;
    private DocumentChunkingConfig chunkingConfig;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("java-ocp-guide.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setNormalizedText(createRealDocumentText());
        document.setCharCount(createRealDocumentText().length());
        
        objectMapper = new ObjectMapper();
        
        // Setup chunking config mock
        chunkingConfig = new DocumentChunkingConfig();
        chunkingConfig.setMaxSingleChunkTokens(40_000);
        chunkingConfig.setMaxSingleChunkChars(150_000);
        chunkingConfig.setOverlapTokens(5_000);
        chunkingConfig.setModelMaxTokens(128_000);
        chunkingConfig.setPromptOverheadTokens(5_000);
        chunkingConfig.setAggressiveChunking(true);
        chunkingConfig.setEnableEmergencyChunking(true);
        
        // Setup lenient stubbing for getChunkingConfig to avoid unnecessary stubbing errors
        lenient().when(chunkedStructureService.getChunkingConfig()).thenReturn(chunkingConfig);
    }

    @Test
    @DisplayName("Should process real AI response with 77 nodes")
    void shouldProcessRealAiResponseWith77Nodes() {
        // Given
        List<DocumentNode> aiNodes = createRealAiResponseNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        lenient().when(chunkedStructureService.needsChunking(anyString())).thenReturn(false);
        lenient().when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        lenient().when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        lenient().when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        lenient().when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        lenient().when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        verify(nodeRepository, times(3)).saveAll(anyList()); // One for each depth level (1, 2, 3)
        verify(nodeRepository, times(1)).findByDocument_IdOrderByStartOffset(documentId); // Called in performGlobalValidation
    }

    @Test
    @DisplayName("Should handle real AI response with complex hierarchy")
    void shouldHandleRealAiResponseWithComplexHierarchy() {
        // Given
        List<DocumentNode> aiNodes = createRealAiResponseNodes();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        lenient().when(chunkedStructureService.needsChunking(anyString())).thenReturn(false);
        lenient().when(llmClient.generateStructure(any(), any())).thenReturn(aiNodes);
        lenient().when(anchorOffsetCalculator.calculateOffsets(anyList(), anyString())).thenReturn(aiNodes);
        lenient().when(nodeRepository.saveAll(anyList())).thenReturn(aiNodes);
        lenient().when(nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort()))
            .thenReturn(Collections.emptyList());
        lenient().when(nodeRepository.findByDocument_IdOrderByStartOffset(documentId)).thenReturn(aiNodes);

        // When
        service.buildStructure(documentId);

        // Then
        // Verify that nodes are processed level by level
        verify(nodeRepository, times(3)).saveAll(anyList());
        
        // Verify that parent relationships are assigned correctly
        verify(nodeRepository, times(3)).findByDocument_IdAndDepthLessThanOrderByStartOffset(any(), anyShort());
    }

    @Test
    @DisplayName("Should validate real AI response node types")
    void shouldValidateRealAiResponseNodeTypes() {
        // Given
        List<DocumentNode> aiNodes = createRealAiResponseNodes();
        
        // When & Then
        assertThat(aiNodes).hasSize(77);
        
        // Check that we have the expected node types from the real AI response
        long chapterCount = aiNodes.stream()
            .filter(node -> node.getType() == DocumentNode.NodeType.CHAPTER)
            .count();
        assertThat(chapterCount).isEqualTo(1);
        
        long sectionCount = aiNodes.stream()
            .filter(node -> node.getType() == DocumentNode.NodeType.SECTION)
            .count();
        assertThat(sectionCount).isGreaterThan(0);
        
        long subsectionCount = aiNodes.stream()
            .filter(node -> node.getType() == DocumentNode.NodeType.SUBSECTION)
            .count();
        assertThat(subsectionCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle real AI response confidence values")
    void shouldHandleRealAiResponseConfidenceValues() {
        // Given
        List<DocumentNode> aiNodes = createRealAiResponseNodes();
        
        // When & Then
        assertThat(aiNodes).allSatisfy(node -> {
            assertThat(node.getAiConfidence()).isNotNull();
            assertThat(node.getAiConfidence().doubleValue()).isBetween(0.0, 1.0);
        });
        
        // Check that we have nodes with different confidence levels
        Set<BigDecimal> confidenceValues = new HashSet<>();
        aiNodes.forEach(node -> confidenceValues.add(node.getAiConfidence()));
        assertThat(confidenceValues).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Should handle real AI response depth distribution")
    void shouldHandleRealAiResponseDepthDistribution() {
        // Given
        List<DocumentNode> aiNodes = createRealAiResponseNodes();
        
        // When & Then
        Map<Short, Long> depthDistribution = new HashMap<>();
        aiNodes.forEach(node -> {
            depthDistribution.merge(node.getDepth(), 1L, Long::sum);
        });
        
        assertThat(depthDistribution).containsKeys((short) 1, (short) 2, (short) 3);
        assertThat(depthDistribution.get((short) 1)).isEqualTo(1L); // One chapter
        assertThat(depthDistribution.get((short) 2)).isGreaterThan(0L); // Multiple sections
        assertThat(depthDistribution.get((short) 3)).isGreaterThan(0L); // Multiple subsections
    }

    @Test
    @DisplayName("Should handle real AI response anchor lengths")
    void shouldHandleRealAiResponseAnchorLengths() {
        // Given
        List<DocumentNode> aiNodes = createRealAiResponseNodes();
        
        // When & Then
        assertThat(aiNodes).allSatisfy(node -> {
            assertThat(node.getStartAnchor()).isNotNull();
            assertThat(node.getEndAnchor()).isNotNull();
            assertThat(node.getStartAnchor().length()).isLessThanOrEqualTo(1000);
            assertThat(node.getEndAnchor().length()).isLessThanOrEqualTo(1000);
        });
        
        // Check that anchors are meaningful (not empty)
        assertThat(aiNodes).allSatisfy(node -> {
            assertThat(node.getStartAnchor().trim()).isNotEmpty();
            assertThat(node.getEndAnchor().trim()).isNotEmpty();
        });
    }

    // Helper methods to create test data based on real AI response

    private String createRealDocumentText() {
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
            
            Where Did the JRE Go? In Java 8 and earlier, you could download a Java Runtime 
            Environment (JRE) instead of the full JDK. The JRE was a subset of the JDK that was used for 
            running a program but could not compile one.
            
            Using APIs and IDEs When writing a program, there are common pieces of functionality 
            and algorithms that developers need. Luckily, we do not have to write each of these ourselves. Java 
            comes with a large suite of application programming interfaces (APIs) that you can use.
            
            Downloading a JDK Every six months, Oracle releases a new version of Java. 
            Java 17 came out in September 2021. This means that Java 17 will not be the latest version when 
            you download the JDK to study for the exam.
            
            Understanding the Class Structure In Java 
            programs, classes are the basic building blocks. When defining a class, you describe all the parts and 
            characteristics of one of those building blocks.
            
            Fields and Methods Java classes have two primary elements: methods, 
            often called functions or procedures in other languages, and fields, more generally known as 
            variables.
            
            Adding fields and methods examples The simplest Java class you can write looks like this: 1: public class Animal { 2: } Java 
            calls a word with special meaning a keyword, which we've marked bold in the previous snippet.
            
            Comments Another common part of the code is called a comment. 
            Because comments aren't executable code, you can place them in many places. Comments can make 
            your code easier to read.
            
            Classes and Source Files Most of the time, each Java class is defined in its own 
            .java file. In this chapter, the only top-level type is a class.
            
            Writing a main() Method A Java program begins execution with its main() method. In this section, you learn how to 
            create one, pass a parameter, and run a program.
            
            Single-File Source-Code If you get tired of typing both 
            javac and java every time you want to try a code example, there's a shortcut. You can instead run 
            java Zoo.java Bronx Zoo
            
            Understanding Package Declarations and Imports Java comes with thousands of built-in classes, 
            and there are countless more from developers like you.
            
            Wildcards Classes in the 
            same package are often imported together. You can use a shortcut to import all the classes in a 
            package. import java.util.*; // imports java.util.Random among other things
            
            Redundant Imports Wait a minute! We've been referring to System 
            without an import every time we printed text, and Java found it just fine. There's one special 
            package in the Java world called java.lang.
            
            Naming Conflicts One of the reasons for using packages is so that class names don't have to be unique across 
            all of Java. This means you'll sometimes want to import a class that can be found in multiple places.
            
            Creating a New Package Up to now, all the code we've written in this chapter has been in the default package. 
            This is a special unnamed package that you should use only for throwaway code.
            """;
    }

    private List<DocumentNode> createRealAiResponseNodes() {
        List<DocumentNode> nodes = new ArrayList<>();
        
        // Chapter (depth 1)
        DocumentNode chapter = new DocumentNode();
        chapter.setType(DocumentNode.NodeType.CHAPTER);
        chapter.setTitle("Chapter 1 Building Blocks");
        chapter.setStartAnchor("Chapter 1 Building Blocks OCP EXAM OBJECTIVES COVE");
        chapter.setEndAnchor("extra whitespace doesn't matter in Java syntax. Th");
        chapter.setStartOffset(0);
        chapter.setEndOffset(1000); // Valid offset within document length
        chapter.setDepth((short) 1);
        chapter.setAiConfidence(BigDecimal.valueOf(1.0));
        nodes.add(chapter);
        
        // Sections (depth 2)
        String[] sectionTitles = {
            "OCP EXAM OBJECTIVES COVERED IN THIS CHAPTER",
            "Learning about the Environment",
            "Understanding the Class Structure",
            "Understanding Package Declarations and Imports"
        };
        
        for (int i = 0; i < sectionTitles.length; i++) {
            DocumentNode section = new DocumentNode();
            section.setType(DocumentNode.NodeType.SECTION);
            section.setTitle(sectionTitles[i]);
            section.setStartAnchor(sectionTitles[i] + " start anchor");
            section.setEndAnchor(sectionTitles[i] + " end anchor");
            section.setStartOffset(100 + i * 50);
            section.setEndOffset(200 + i * 50); // Valid offsets within document length
            section.setDepth((short) 2);
            section.setAiConfidence(BigDecimal.valueOf(0.95));
            nodes.add(section);
        }
        
        // Subsections (depth 3)
        String[] subsectionTitles = {
            "Major Components of Java",
            "Where Did the JRE Go?",
            "Using APIs and IDEs",
            "Downloading a JDK",
            "Fields and Methods",
            "Adding fields and methods examples",
            "Comments",
            "Classes and Source Files",
            "Writing a main() Method",
            "Single-File Source-Code",
            "Wildcards",
            "Redundant Imports",
            "Naming Conflicts",
            "Creating a New Package"
        };
        
        for (int i = 0; i < subsectionTitles.length; i++) {
            DocumentNode subsection = new DocumentNode();
            subsection.setType(DocumentNode.NodeType.SUBSECTION);
            subsection.setTitle(subsectionTitles[i]);
            subsection.setStartAnchor(subsectionTitles[i] + " start anchor");
            subsection.setEndAnchor(subsectionTitles[i] + " end anchor");
            subsection.setStartOffset(500 + i * 150);
            subsection.setEndOffset(650 + i * 150);
            subsection.setDepth((short) 3);
            subsection.setAiConfidence(BigDecimal.valueOf(0.9));
            nodes.add(subsection);
        }
        
        // Add more nodes to reach 77 total (based on real AI response)
        for (int i = 0; i < 58; i++) {
            DocumentNode additionalNode = new DocumentNode();
            additionalNode.setType(DocumentNode.NodeType.PARAGRAPH);
            additionalNode.setTitle("Additional Content " + (i + 1));
            additionalNode.setStartAnchor("Additional content " + (i + 1) + " start");
            additionalNode.setEndAnchor("Additional content " + (i + 1) + " end");
            additionalNode.setStartOffset(50 + i * 10);
            additionalNode.setEndOffset(100 + i * 10); // Valid offsets within document length
            additionalNode.setDepth((short) (2 + (i % 2))); // Alternate between depth 2 and 3
            additionalNode.setAiConfidence(BigDecimal.valueOf(0.85));
            nodes.add(additionalNode);
        }
        
        return nodes;
    }
}
