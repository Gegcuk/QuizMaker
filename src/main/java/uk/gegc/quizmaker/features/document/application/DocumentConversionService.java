package uk.gegc.quizmaker.service.document.converter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.exception.DocumentProcessingException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Main service for document conversion.
 * <p>
 * This service orchestrates the conversion process by:
 * 1. Finding the appropriate converter using the factory
 * 2. Converting the input file to the standardized ConvertedDocument format
 * 3. Providing a unified interface for all document processing
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentConversionService {

    private final DocumentConverterFactory converterFactory;

    /**
     * Convert a document file to the standardized ConvertedDocument format
     */
    public ConvertedDocument convertDocument(byte[] fileContent, String filename, String contentType) {
        try {
            log.info("Starting document conversion for file: {} (content type: {})", filename, contentType);

            // Find the appropriate converter
            DocumentConverter converter = converterFactory.findConverter(contentType, filename);

            // Convert the document
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                ConvertedDocument convertedDocument = converter.convert(inputStream, filename, (long) fileContent.length);

                log.info("Successfully converted document: {} ({} characters, {} chapters)",
                        filename, convertedDocument.getFullContent().length(), convertedDocument.getChapters().size());

                return convertedDocument;
            }

        } catch (Exception e) {
            String errorMessage = String.format("Failed to convert document %s: %s", filename, e.getMessage());
            log.error(errorMessage, e);
            throw new DocumentProcessingException(errorMessage, e);
        }
    }

    /**
     * Get all supported content types
     */
    public List<String> getSupportedContentTypes() {
        return converterFactory.getSupportedContentTypes();
    }

    /**
     * Get all supported file extensions
     */
    public List<String> getSupportedExtensions() {
        return converterFactory.getSupportedExtensions();
    }

    /**
     * Check if a file type is supported
     */
    public boolean isSupported(String contentType, String filename) {
        try {
            converterFactory.findConverter(contentType, filename);
            return true;
        } catch (DocumentProcessingException e) {
            return false;
        }
    }

    /**
     * Get information about all available converters
     */
    public List<ConverterInfo> getConverterInfo() {
        return converterFactory.getAllConverters().stream()
                .map(converter -> new ConverterInfo(
                        converter.getConverterType(),
                        converter.getSupportedContentTypes(),
                        converter.getSupportedExtensions()
                ))
                .toList();
    }

    /**
     * Information about a converter
     */
    public record ConverterInfo(
            String converterType,
            List<String> supportedContentTypes,
            List<String> supportedExtensions
    ) {
    }
} 