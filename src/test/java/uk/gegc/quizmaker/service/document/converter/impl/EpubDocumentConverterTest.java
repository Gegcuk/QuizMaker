package uk.gegc.quizmaker.service.document.converter.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;
import uk.gegc.quizmaker.features.document.infra.converter.EpubDocumentConverter;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
@SpringBootTest
@ActiveProfiles("test")
class EpubDocumentConverterTest {

    private final DocumentConverter converter = new EpubDocumentConverter();

    @Autowired
    private DocumentConverterFactory converterFactory;

    @Test
    void canConvert_EpubContentType_ReturnsTrue() {
        // Act
        boolean result = converter.canConvert("application/epub+zip", "test.epub");

        // Assert
        assertTrue(result);
    }

    @Test
    void canConvert_EpubExtension_ReturnsTrue() {
        // Act
        boolean result = converter.canConvert("application/octet-stream", "test.epub");

        // Assert
        assertTrue(result);
    }

    @Test
    void canConvert_NonEpubContentType_ReturnsFalse() {
        // Act
        boolean result = converter.canConvert("application/pdf", "test.pdf");

        // Assert
        assertFalse(result);
    }

    @Test
    void canConvert_NonEpubExtension_ReturnsFalse() {
        // Act
        boolean result = converter.canConvert("application/pdf", "test.pdf");

        // Assert
        assertFalse(result);
    }

    @Test
    void canConvert_EpubContentTypeWithWrongExtension_ReturnsTrue() {
        // Act - This should work because content type is correct, even if extension is wrong
        boolean result = converter.canConvert("application/epub+zip", "test.pdf");

        // Assert
        assertTrue(result, "Should accept EPUB content type even with wrong extension");
    }

    @Test
    void getSupportedContentTypes_ReturnsEpubContentTypes() {
        // Act
        List<String> result = converter.getSupportedContentTypes();

        // Assert
        assertTrue(result.contains("application/epub+zip"));
        assertTrue(result.contains("application/epub"));
        assertTrue(result.contains("application/x-epub"));
    }

    @Test
    void getSupportedExtensions_ReturnsEpubExtension() {
        // Act
        List<String> result = converter.getSupportedExtensions();

        // Assert
        assertEquals(Arrays.asList(".epub"), result);
    }

    @Test
    void getConverterType_ReturnsEpubConverterType() {
        // Act
        String result = converter.getConverterType();

        // Assert
        assertEquals("EPUB_DOCUMENT_CONVERTER", result);
    }

    @Test
    void convert_InvalidEpubContent_HandlesGracefully() throws Exception {
        // Arrange
        byte[] invalidContent = "This is not a valid EPUB file".getBytes();
        String filename = "test.epub";
        Long fileSize = (long) invalidContent.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(invalidContent), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertEquals("application/epub+zip", result.getContentType());
        assertEquals(fileSize, result.getFileSize());
        assertEquals("EPUB_DOCUMENT_CONVERTER", result.getConverterType());
        assertEquals("This is not a valid EPUB file", result.getFullContent().trim());
        assertEquals(1, result.getChapters().size());
        assertEquals("Document", result.getChapters().get(0).getTitle());
    }

    @Test
    void convert_NullInputStream_ThrowsException() {
        // Arrange
        String filename = "test.epub";
        Long fileSize = 100L;

        // Act & Assert
        assertThrows(Exception.class, () -> {
            converter.convert(null, filename, fileSize);
        });
    }

    @Test
    void factory_FindConverter_EpubFile_ReturnsEpubConverter() {
        // Act
        DocumentConverter foundConverter = converterFactory.findConverter("application/epub+zip", "test.epub");

        // Assert
        assertNotNull(foundConverter);
        assertEquals("EPUB_DOCUMENT_CONVERTER", foundConverter.getConverterType());
    }

    @Test
    void factory_FindConverter_EpubFileByExtension_ReturnsEpubConverter() {
        // Act
        DocumentConverter foundConverter = converterFactory.findConverter("application/octet-stream", "test.epub");

        // Assert
        assertNotNull(foundConverter);
        assertEquals("EPUB_DOCUMENT_CONVERTER", foundConverter.getConverterType());
    }
} 