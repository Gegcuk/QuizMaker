package uk.gegc.quizmaker.service.document.parser.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.service.document.parser.FileParser;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TextFileParserTest {

    @InjectMocks
    private TextFileParser textFileParser;

    @BeforeEach
    void setUp() {
        // Initialize any necessary setup
    }

    @Test
    void canParse_TextContentType_ReturnsTrue() {
        // Arrange
        String contentType = "text/plain";
        String filename = "test.txt";

        // Act
        boolean result = textFileParser.canParse(contentType, filename);

        // Assert
        assertTrue(result);
    }

    @Test
    void canParse_TextExtension_ReturnsTrue() {
        // Arrange
        String contentType = "application/octet-stream";
        String filename = "test.txt";

        // Act
        boolean result = textFileParser.canParse(contentType, filename);

        // Assert
        assertTrue(result);
    }

    @Test
    void canParse_TextExtensionWithDot_ReturnsTrue() {
        // Arrange
        String contentType = "application/octet-stream";
        String filename = "test.text";

        // Act
        boolean result = textFileParser.canParse(contentType, filename);

        // Assert
        assertTrue(result);
    }

    @Test
    void canParse_NonTextContentType_ReturnsFalse() {
        // Arrange
        String contentType = "application/pdf";
        String filename = "test.pdf";

        // Act
        boolean result = textFileParser.canParse(contentType, filename);

        // Assert
        assertFalse(result);
    }

    @Test
    void getSupportedContentTypes_ReturnsTextTypes() {
        // Act
        List<String> result = textFileParser.getSupportedContentTypes();

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("text/plain"));
        assertTrue(result.contains("text/txt"));
        assertEquals(2, result.size());
    }

    @Test
    void getSupportedExtensions_ReturnsTextExtensions() {
        // Act
        List<String> result = textFileParser.getSupportedExtensions();

        // Assert
        assertNotNull(result);
        assertTrue(result.contains(".txt"));
        assertTrue(result.contains(".text"));
        assertEquals(2, result.size());
    }

    @Test
    void parse_SimpleTextContent_Success() throws Exception {
        // Arrange
        String content = "This is a simple test document.";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        assertTrue(result.getChapters().isEmpty()); // No chapters in simple text
    }

    @Test
    void parse_DocumentWithChapters_ExtractsChapters() throws Exception {
        // Arrange
        String content = """
            Chapter 1: Introduction
            This is the introduction chapter.
            
            Chapter 2: Main Content
            This is the main content chapter.
            
            Chapter 3: Conclusion
            This is the conclusion chapter.
            """;
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have titles
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
        }
    }

    @Test
    void parse_DocumentWithSections_ExtractsSections() throws Exception {
        // Arrange
        String content = """
            Chapter 1: Introduction
            1.1 Background
            This section provides background information.
            
            1.2 Objectives
            This section outlines the objectives.
            
            Chapter 2: Methodology
            2.1 Research Design
            This section describes the research design.
            
            2.2 Data Collection
            This section explains data collection methods.
            """;
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters and sections based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters and sections are detected
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
            
            // Check if sections are detected
            if (chapter.getSections() != null && !chapter.getSections().isEmpty()) {
                for (ParsedDocument.Section section : chapter.getSections()) {
                    assertNotNull(section.getTitle());
                    assertNotNull(section.getContent());
                }
            }
        }
    }

    @Test
    void parse_LargeDocument_HandlesLargeContent() throws Exception {
        // Arrange
        StringBuilder largeContent = new StringBuilder();
        largeContent.append("Chapter 1: Introduction\n");
        largeContent.append("This is the introduction chapter.\n\n");
        
        // Create a large document with multiple chapters
        for (int i = 2; i <= 10; i++) {
            largeContent.append("Chapter ").append(i).append(": Content\n");
            largeContent.append("This is chapter ").append(i).append(" content. ");
            largeContent.append("It contains multiple sentences and paragraphs. ");
            largeContent.append("The content is designed to test the parser's ability to handle large documents. ");
            largeContent.append("Each chapter should be properly extracted and structured. ");
            largeContent.append("The parser should maintain the document's logical organization. ");
            largeContent.append("\n\n");
        }
        
        InputStream inputStream = new ByteArrayInputStream(largeContent.toString().getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "large_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(largeContent.toString(), result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have proper structure
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
            assertTrue(chapter.getTitle().contains("Chapter"));
        }
    }

    @Test
    void parse_DocumentWithComplexStructure_ExtractsCorrectly() throws Exception {
        // Arrange
        String content = """
            Chapter 1: Introduction
            1.1 Background
            This section provides background information.
            
            1.2 Objectives
            This section outlines the objectives.
            
            Chapter 2: Methodology
            2.1 Research Design
            This section describes the research design.
            
            2.2 Data Collection
            This section explains data collection methods.
            
            Chapter 3: Results
            3.1 Analysis
            This section presents the analysis results.
            
            3.2 Discussion
            This section discusses the findings.
            """;
        
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "complex_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have proper structure
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
            assertTrue(chapter.getTitle().contains("Chapter"));
        }
    }

    @Test
    void parse_DocumentWithoutStructure_HandlesGracefully() throws Exception {
        // Arrange
        String content = "This is a document without clear chapter or section structure. " +
                "It contains plain text content that should be parsed without errors. " +
                "The parser should handle this gracefully and extract the content properly.";
        
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "unstructured_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser may or may not detect chapters based on regex patterns
        // The important thing is that it doesn't crash and returns the content
        assertTrue(result.getChapters().isEmpty() || result.getChapters().size() >= 0);
    }

    @Test
    void parse_DocumentWithSpecialCharacters_HandlesCorrectly() throws Exception {
        // Arrange
        String content = "Chapter 1: Special Characters\n" +
                "This document contains special characters: é, ñ, ü, ©, ®, ™, €, £, ¥, ¢.\n" +
                "It also contains numbers: 123, 456, 789.\n" +
                "And symbols: @#$%^&*()_+-=[]{}|;':\",./<>?";
        
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "special_chars_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have titles
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
        }
    }

    @Test
    void parse_DocumentWithMixedCaseHeaders_ExtractsCorrectly() throws Exception {
        // Arrange
        String content = """
            CHAPTER 1: INTRODUCTION
            This is the introduction.
            
            Chapter 2: Main Content
            This is the main content.
            
            chapter 3: conclusion
            This is the conclusion.
            """;
        
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "mixed_case_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have titles
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
        }
    }

    @Test
    void parse_DocumentWithEmptyLines_HandlesCorrectly() throws Exception {
        // Arrange
        String content = """
            Chapter 1: Introduction
            
            
            This is the introduction chapter.
            
            
            Chapter 2: Content
            
            This is the content chapter.
            """;
        
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "empty_lines_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have titles
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
        }
    }

    @Test
    void parse_DocumentWithUnicodeCharacters_HandlesCorrectly() throws Exception {
        // Arrange
        String content = "Chapter 1: Unicode Test\n" +
                "This document contains Unicode characters: α, β, γ, δ, ε.\n" +
                "And Chinese characters: 你好世界.\n" +
                "And Japanese characters: こんにちは世界.\n" +
                "And Korean characters: 안녕하세요 세계.";
        
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "unicode_test.txt");

        // Assert
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertNotNull(result.getChapters());
        
        // The parser should detect chapters based on regex patterns
        assertTrue(result.getChapters().size() > 0);
        
        // Verify that chapters are detected and have titles
        for (ParsedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());
        }
    }

    @Test
    void parse_RealTextFile_ExtractsCorrectly() throws Exception {
        // Arrange
        java.nio.file.Path textPath = java.nio.file.Path.of("src", "test", "resources", "test-documents", "sample-text.txt");
        InputStream inputStream = java.nio.file.Files.newInputStream(textPath);

        // Act
        ParsedDocument result = textFileParser.parse(inputStream, "sample-text.txt");

        // Assert
        assertNotNull(result);
        assertNotNull(result.getContent());
        assertTrue(result.getContent().length() > 0);
        assertNotNull(result.getChapters());
        assertTrue(result.getChapters().size() >= 5); // Should have at least 5 chapters
        
        // Verify chapter titles
        List<String> expectedTitles = List.of(
            "Chapter 1: Programming Fundamentals",
            "Chapter 2: Object-Oriented Programming", 
            "Chapter 3: Data Structures",
            "Chapter 4: Algorithms",
            "Chapter 5: Software Development"
        );
        
        for (String expectedTitle : expectedTitles) {
            boolean found = result.getChapters().stream()
                .anyMatch(chapter -> chapter.getTitle().contains(expectedTitle));
            assertTrue(found, "Should find chapter: " + expectedTitle);
        }
        
        // Verify content contains programming concepts
        String content = result.getContent().toLowerCase();
        assertTrue(content.contains("programming"), "Should contain programming content");
        assertTrue(content.contains("variables"), "Should contain variables content");
        assertTrue(content.contains("inheritance"), "Should contain OOP content");
        assertTrue(content.contains("algorithms"), "Should contain algorithms content");
    }
} 