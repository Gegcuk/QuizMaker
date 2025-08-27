package uk.gegc.quizmaker.service.document.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConversionService;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
class DocumentConversionServiceTest {

    @Mock
    private DocumentConverterFactory converterFactory;

    @Mock
    private DocumentConverter mockConverter;

    private DocumentConversionService conversionService;

    @BeforeEach
    void setUp() {
        conversionService = new DocumentConversionService(converterFactory);
    }

    @Test
    void convertDocument_Success() throws Exception {
        // Arrange
        byte[] fileContent = "test content".getBytes();
        String filename = "test.pdf";
        String contentType = "application/pdf";

        ConvertedDocument expectedConvertedDocument = new ConvertedDocument();
        expectedConvertedDocument.setFullContent("test content");
        expectedConvertedDocument.setOriginalFilename(filename);
        expectedConvertedDocument.setContentType(contentType);
        expectedConvertedDocument.setConverterType("PDF_DOCUMENT_CONVERTER");

        when(converterFactory.findConverter(contentType, filename)).thenReturn(mockConverter);
        when(mockConverter.convert(any(ByteArrayInputStream.class), eq(filename), eq((long) fileContent.length)))
                .thenReturn(expectedConvertedDocument);

        // Act
        ConvertedDocument result = conversionService.convertDocument(fileContent, filename, contentType);

        // Assert
        assertNotNull(result);
        assertEquals("test content", result.getFullContent());
        assertEquals(filename, result.getOriginalFilename());
        assertEquals(contentType, result.getContentType());
        assertEquals("PDF_DOCUMENT_CONVERTER", result.getConverterType());
    }

    @Test
    void convertDocument_NoConverterFound_ThrowsException() {
        // Arrange
        byte[] fileContent = "test content".getBytes();
        String filename = "test.unknown";
        String contentType = "application/unknown";

        when(converterFactory.findConverter(contentType, filename))
                .thenThrow(new DocumentProcessingException("No converter found"));

        // Act & Assert
        DocumentProcessingException exception = assertThrows(
                DocumentProcessingException.class,
                () -> conversionService.convertDocument(fileContent, filename, contentType)
        );

        assertTrue(exception.getMessage().contains("Failed to convert document"));
    }

    @Test
    void convertDocument_ConverterThrowsException_ThrowsDocumentProcessingException() throws Exception {
        // Arrange
        byte[] fileContent = "test content".getBytes();
        String filename = "test.pdf";
        String contentType = "application/pdf";

        when(converterFactory.findConverter(contentType, filename)).thenReturn(mockConverter);
        when(mockConverter.convert(any(ByteArrayInputStream.class), eq(filename), eq((long) fileContent.length)))
                .thenThrow(new RuntimeException("Converter error"));

        // Act & Assert
        DocumentProcessingException exception = assertThrows(
                DocumentProcessingException.class,
                () -> conversionService.convertDocument(fileContent, filename, contentType)
        );

        assertTrue(exception.getMessage().contains("Failed to convert document"));
        assertNotNull(exception.getCause());
    }

    @Test
    void getSupportedContentTypes_ReturnsFromFactory() {
        // Arrange
        List<String> expectedContentTypes = Arrays.asList("application/pdf", "text/plain");
        when(converterFactory.getSupportedContentTypes()).thenReturn(expectedContentTypes);

        // Act
        List<String> result = conversionService.getSupportedContentTypes();

        // Assert
        assertEquals(expectedContentTypes, result);
    }

    @Test
    void getSupportedExtensions_ReturnsFromFactory() {
        // Arrange
        List<String> expectedExtensions = Arrays.asList(".pdf", ".txt");
        when(converterFactory.getSupportedExtensions()).thenReturn(expectedExtensions);

        // Act
        List<String> result = conversionService.getSupportedExtensions();

        // Assert
        assertEquals(expectedExtensions, result);
    }

    @Test
    void isSupported_ConverterFound_ReturnsTrue() {
        // Arrange
        String filename = "test.pdf";
        String contentType = "application/pdf";
        when(converterFactory.findConverter(contentType, filename)).thenReturn(mockConverter);

        // Act
        boolean result = conversionService.isSupported(contentType, filename);

        // Assert
        assertTrue(result);
    }

    @Test
    void isSupported_NoConverterFound_ReturnsFalse() {
        // Arrange
        String filename = "test.unknown";
        String contentType = "application/unknown";
        when(converterFactory.findConverter(contentType, filename))
                .thenThrow(new DocumentProcessingException("No converter found"));

        // Act
        boolean result = conversionService.isSupported(contentType, filename);

        // Assert
        assertFalse(result);
    }

    @Test
    void getConverterInfo_ReturnsConverterInformation() {
        // Arrange
        List<DocumentConverter> converters = Arrays.asList(mockConverter);
        when(converterFactory.getAllConverters()).thenReturn(converters);
        when(mockConverter.getConverterType()).thenReturn("PDF_DOCUMENT_CONVERTER");
        when(mockConverter.getSupportedContentTypes()).thenReturn(Arrays.asList("application/pdf"));
        when(mockConverter.getSupportedExtensions()).thenReturn(Arrays.asList(".pdf"));

        // Act
        List<DocumentConversionService.ConverterInfo> result = conversionService.getConverterInfo();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("PDF_DOCUMENT_CONVERTER", result.get(0).converterType());
        assertEquals(Arrays.asList("application/pdf"), result.get(0).supportedContentTypes());
        assertEquals(Arrays.asList(".pdf"), result.get(0).supportedExtensions());
    }
} 