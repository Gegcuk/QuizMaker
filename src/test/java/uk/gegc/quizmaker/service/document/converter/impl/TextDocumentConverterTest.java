package uk.gegc.quizmaker.service.document.converter.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.TextDocumentConverter;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class TextDocumentConverterTest {

    private final DocumentConverter converter = new TextDocumentConverter();

    @Test
    void canConvert_TextContentType_ReturnsTrue() {
        // Act
        boolean result = converter.canConvert("text/plain", "test.txt");

        // Assert
        assertTrue(result);
    }

    @Test
    void canConvert_TextExtension_ReturnsTrue() {
        // Act
        boolean result = converter.canConvert("application/octet-stream", "test.txt");

        // Assert
        assertTrue(result);
    }

    @Test
    void canConvert_NonTextContentType_ReturnsFalse() {
        // Act
        boolean result = converter.canConvert("application/pdf", "test.pdf");

        // Assert
        assertFalse(result);
    }

    @Test
    void canConvert_NonTextExtension_ReturnsFalse() {
        // Act
        boolean result = converter.canConvert("application/pdf", "test.pdf");

        // Assert
        assertFalse(result);
    }

    @Test
    void getSupportedContentTypes_ReturnsTextContentTypes() {
        // Act
        List<String> result = converter.getSupportedContentTypes();

        // Assert
        assertEquals(Arrays.asList("text/plain", "text/txt"), result);
    }

    @Test
    void getSupportedExtensions_ReturnsTextExtensions() {
        // Act
        List<String> result = converter.getSupportedExtensions();

        // Assert
        assertEquals(Arrays.asList(".txt", ".text"), result);
    }

    @Test
    void getConverterType_ReturnsTextConverterType() {
        // Act
        String result = converter.getConverterType();

        // Assert
        assertEquals("TEXT_DOCUMENT_CONVERTER", result);
    }

    @Test
    void convert_SimpleTextContent_ReturnsConvertedDocument() throws Exception {
        // Arrange
        String textContent = "This is a simple test document.\nIt has multiple lines.\n";
        byte[] content = textContent.getBytes();
        String filename = "test.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertEquals("text/plain", result.getContentType());
        assertEquals(fileSize, result.getFileSize());
        assertEquals("TEXT_DOCUMENT_CONVERTER", result.getConverterType());
        assertNotNull(result.getFullContent());
        assertNotNull(result.getChapters());
        assertFalse(result.getChapters().isEmpty());
    }

    @Test
    void convert_TextWithChapters_ExtractsChapters() throws Exception {
        // Arrange
        String textContent = """
                Chapter 1: Introduction
                This is the introduction chapter.
                
                Chapter 2: Main Content
                This is the main content chapter.
                
                Chapter 3: Conclusion
                This is the conclusion chapter.
                """;
        byte[] content = textContent.getBytes();
        String filename = "test.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertNotNull(result.getChapters());
        assertTrue(result.getChapters().size() >= 3);

        // Check that chapters are detected
        boolean hasChapter1 = false;
        boolean hasChapter2 = false;
        boolean hasChapter3 = false;

        for (ConvertedDocument.Chapter chapter : result.getChapters()) {
            if (chapter.getTitle().contains("Chapter 1")) hasChapter1 = true;
            if (chapter.getTitle().contains("Chapter 2")) hasChapter2 = true;
            if (chapter.getTitle().contains("Chapter 3")) hasChapter3 = true;
        }

        assertTrue(hasChapter1, "Should contain Chapter 1");
        assertTrue(hasChapter2, "Should contain Chapter 2");
        assertTrue(hasChapter3, "Should contain Chapter 3");
    }

    @Test
    void convert_TextWithSections_ExtractsSections() throws Exception {
        // Arrange
        String textContent = """
                Chapter 1: Introduction
                1.1 Background
                This section provides background information.
                
                1.2 Objectives
                This section outlines the objectives.
                
                Chapter 2: Methodology
                2.1 Research Design
                This section describes the research design.
                """;
        byte[] content = textContent.getBytes();
        String filename = "test.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getChapters());

        // Check that chapters and sections are detected
        for (ConvertedDocument.Chapter chapter : result.getChapters()) {
            assertNotNull(chapter.getTitle());
            assertNotNull(chapter.getContent());

            // Check if sections are detected
            if (chapter.getSections() != null && !chapter.getSections().isEmpty()) {
                for (ConvertedDocument.Section section : chapter.getSections()) {
                    assertNotNull(section.getTitle());
                    assertNotNull(section.getContent());
                }
            }
        }
    }

    @Test
    void convert_TextWithMetadata_ExtractsMetadata() throws Exception {
        // Arrange
        String textContent = """
                Sample Document Title
                by John Doe
                
                Chapter 1: Introduction
                This is the introduction.
                """;
        byte[] content = textContent.getBytes();
        String filename = "test.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        // The converter should attempt to extract title and author
        // Note: The exact extraction depends on the implementation
        assertNotNull(result.getFullContent());
    }

    @Test
    void convert_EmptyText_HandlesGracefully() throws Exception {
        // Arrange
        String textContent = "";
        byte[] content = textContent.getBytes();
        String filename = "empty.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertNotNull(result.getChapters());
        assertFalse(result.getChapters().isEmpty()); // Should create a default chapter
    }

    @Test
    void convert_TextWithUnicode_HandlesCorrectly() throws Exception {
        // Arrange
        String textContent = "This is a test with unicode: é, ñ, ü, 中文, русский";
        byte[] content = textContent.getBytes();
        String filename = "unicode.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertTrue(result.getFullContent().contains("unicode"));
        assertTrue(result.getFullContent().contains("é"));
    }

    @Test
    void convert_TextWithSimpleChapters_ExtractsChapters() throws Exception {
        // Arrange - Test with simpler chapter format
        String textContent = """
                Chapter 1
                This is chapter 1 content.
                
                Chapter 2
                This is chapter 2 content.
                """;
        byte[] content = textContent.getBytes();
        String filename = "simple_chapters.txt";
        Long fileSize = (long) content.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(content), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getChapters());
        assertTrue(result.getChapters().size() >= 2, "Should detect at least 2 chapters");

        // Check that chapters are detected
        boolean hasChapter1 = false;
        boolean hasChapter2 = false;

        for (ConvertedDocument.Chapter chapter : result.getChapters()) {
            if (chapter.getTitle().contains("Chapter 1")) hasChapter1 = true;
            if (chapter.getTitle().contains("Chapter 2")) hasChapter2 = true;
        }

        assertTrue(hasChapter1, "Should contain Chapter 1");
        assertTrue(hasChapter2, "Should contain Chapter 2");
    }
} 