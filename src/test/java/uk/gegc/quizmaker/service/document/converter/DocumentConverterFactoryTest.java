package uk.gegc.quizmaker.service.document.converter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DocumentConverterFactoryTest {

    @Autowired
    private DocumentConverterFactory converterFactory;

    @Test
    void getAllConverters_ReturnsAllConverters() {
        // Act
        List<DocumentConverter> converters = converterFactory.getAllConverters();

        // Assert
        assertNotNull(converters);
        assertFalse(converters.isEmpty());
        
        // Check that we have the expected converters
        boolean hasPdfConverter = false;
        boolean hasTextConverter = false;
        boolean hasEpubConverter = false;
        
        for (DocumentConverter converter : converters) {
            String converterType = converter.getConverterType();
            if ("PDF_DOCUMENT_CONVERTER".equals(converterType)) {
                hasPdfConverter = true;
            } else if ("TEXT_DOCUMENT_CONVERTER".equals(converterType)) {
                hasTextConverter = true;
            } else if ("EPUB_DOCUMENT_CONVERTER".equals(converterType)) {
                hasEpubConverter = true;
            }
        }
        
        assertTrue(hasPdfConverter, "Should have PDF converter");
        assertTrue(hasTextConverter, "Should have Text converter");
        assertTrue(hasEpubConverter, "Should have EPUB converter");
    }

    @Test
    void findConverter_PdfFile_ReturnsPdfConverter() {
        // Act
        DocumentConverter converter = converterFactory.findConverter("application/pdf", "test.pdf");

        // Assert
        assertNotNull(converter);
        assertEquals("PDF_DOCUMENT_CONVERTER", converter.getConverterType());
    }

    @Test
    void findConverter_TextFile_ReturnsTextConverter() {
        // Act
        DocumentConverter converter = converterFactory.findConverter("text/plain", "test.txt");

        // Assert
        assertNotNull(converter);
        assertEquals("TEXT_DOCUMENT_CONVERTER", converter.getConverterType());
    }

    @Test
    void findConverter_EpubFile_ReturnsEpubConverter() {
        // Act
        DocumentConverter converter = converterFactory.findConverter("application/epub+zip", "test.epub");

        // Assert
        assertNotNull(converter);
        assertEquals("EPUB_DOCUMENT_CONVERTER", converter.getConverterType());
    }

    @Test
    void findConverter_EpubFileByExtension_ReturnsEpubConverter() {
        // Act
        DocumentConverter converter = converterFactory.findConverter("application/octet-stream", "test.epub");

        // Assert
        assertNotNull(converter);
        assertEquals("EPUB_DOCUMENT_CONVERTER", converter.getConverterType());
    }

    @Test
    void getSupportedContentTypes_ReturnsAllContentTypes() {
        // Act
        List<String> contentTypes = converterFactory.getSupportedContentTypes();

        // Assert
        assertNotNull(contentTypes);
        assertTrue(contentTypes.contains("application/pdf"));
        assertTrue(contentTypes.contains("text/plain"));
        assertTrue(contentTypes.contains("application/epub+zip"));
    }

    @Test
    void getSupportedExtensions_ReturnsAllExtensions() {
        // Act
        List<String> extensions = converterFactory.getSupportedExtensions();

        // Assert
        assertNotNull(extensions);
        assertTrue(extensions.contains(".pdf"));
        assertTrue(extensions.contains(".txt"));
        assertTrue(extensions.contains(".epub"));
    }
} 