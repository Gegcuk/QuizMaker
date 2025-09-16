package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.util.List;

/**
 * Factory class for document converters.
 * <p>
 * This implements the Factory pattern to find the appropriate converter
 * for a given file type. It automatically discovers all available converters
 * and selects the one that can handle the input format.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentConverterFactory {

    private final List<DocumentConverter> converters;

    /**
     * Find the appropriate converter for the given file type
     */
    public DocumentConverter findConverter(String contentType, String filename) {
        log.info("Looking for converter for content type: {}, filename: {}", contentType, filename);
        log.info("Available converters: {}", converters.stream().map(DocumentConverter::getConverterType).toList());

        for (DocumentConverter converter : converters) {
            if (converter.canConvert(contentType, filename)) {
                log.info("Found converter: {}", converter.getConverterType());
                return converter;
            }
        }

        String errorMessage = String.format("No converter found for content type: %s, filename: %s", contentType, filename);
        log.error(errorMessage);
        log.error("Available converters: {}", converters.stream().map(DocumentConverter::getConverterType).toList());
        log.error("Supported content types: {}", getSupportedContentTypes());
        log.error("Supported extensions: {}", getSupportedExtensions());
        throw new DocumentProcessingException(errorMessage);
    }

    /**
     * Get all available converters
     */
    public List<DocumentConverter> getAllConverters() {
        return converters;
    }

    /**
     * Get supported content types across all converters
     */
    public List<String> getSupportedContentTypes() {
        return converters.stream()
                .flatMap(converter -> converter.getSupportedContentTypes().stream())
                .distinct()
                .toList();
    }

    /**
     * Get supported file extensions across all converters
     */
    public List<String> getSupportedExtensions() {
        return converters.stream()
                .flatMap(converter -> converter.getSupportedExtensions().stream())
                .distinct()
                .toList();
    }
} 