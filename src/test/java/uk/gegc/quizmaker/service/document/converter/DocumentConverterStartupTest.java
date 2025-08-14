package uk.gegc.quizmaker.service.document.converter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DocumentConverterStartupTest {

    @Autowired
    private DocumentConverterFactory converterFactory;

    @Test
    void contextLoads_AllConvertersRegistered() {
        // Act
        List<DocumentConverter> converters = converterFactory.getAllConverters();

        // Assert
        assertNotNull(converters);
        assertFalse(converters.isEmpty());

        // Log all converters for debugging
        System.out.println("Registered converters:");
        for (DocumentConverter converter : converters) {
            System.out.println("  - " + converter.getConverterType());
            System.out.println("    Content types: " + converter.getSupportedContentTypes());
            System.out.println("    Extensions: " + converter.getSupportedExtensions());
        }

        // Verify we have at least 3 converters (PDF, Text, EPUB)
        assertTrue(converters.size() >= 3, "Should have at least 3 converters");

        // Check for specific converters
        boolean hasPdfConverter = converters.stream()
                .anyMatch(c -> "PDF_DOCUMENT_CONVERTER".equals(c.getConverterType()));
        boolean hasTextConverter = converters.stream()
                .anyMatch(c -> "TEXT_DOCUMENT_CONVERTER".equals(c.getConverterType()));
        boolean hasEpubConverter = converters.stream()
                .anyMatch(c -> "EPUB_DOCUMENT_CONVERTER".equals(c.getConverterType()));

        assertTrue(hasPdfConverter, "PDF converter should be registered");
        assertTrue(hasTextConverter, "Text converter should be registered");
        assertTrue(hasEpubConverter, "EPUB converter should be registered");
    }

    @Test
    void converterFactory_SupportsAllExpectedContentTypes() {
        // Act
        List<String> supportedContentTypes = converterFactory.getSupportedContentTypes();

        // Assert
        assertNotNull(supportedContentTypes);
        assertTrue(supportedContentTypes.contains("application/pdf"));
        assertTrue(supportedContentTypes.contains("text/plain"));
        assertTrue(supportedContentTypes.contains("application/epub+zip"));
        assertTrue(supportedContentTypes.contains("application/epub"));
        assertTrue(supportedContentTypes.contains("application/x-epub"));
    }

    @Test
    void converterFactory_SupportsAllExpectedExtensions() {
        // Act
        List<String> supportedExtensions = converterFactory.getSupportedExtensions();

        // Assert
        assertNotNull(supportedExtensions);
        assertTrue(supportedExtensions.contains(".pdf"));
        assertTrue(supportedExtensions.contains(".txt"));
        assertTrue(supportedExtensions.contains(".epub"));
    }
} 