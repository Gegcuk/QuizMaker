package uk.gegc.quizmaker.features.conversion.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;

import java.util.List;

/**
 * Service for converting documents to text using appropriate converters.
 * Uses strategy pattern to delegate to specific converters.
 */
@Service("documentProcessConversionService")
@RequiredArgsConstructor
@Slf4j
public class DocumentConversionService {

    private final List<DocumentConverter> converters;
    private final MimeTypeDetector mimeTypeDetector;

    /**
     * Converts document bytes to text using the appropriate converter.
     * 
     * @param originalName the original filename (used for format detection)
     * @param bytes the document bytes
     * @return ConversionResult containing the extracted text
     * @throws UnsupportedFormatException if no suitable converter is found
     * @throws ConversionException if conversion fails
     */
    public ConversionResult convert(String originalName, byte[] bytes) throws ConversionException {
        log.debug("Converting document: {} ({} bytes)", originalName, bytes.length);
        
        String mimeType = mimeTypeDetector.detectMimeType(originalName);
        DocumentConverter converter = findConverter(originalName);
        if (converter == null && mimeType != null) {
            converter = findConverter(mimeType);
        }
        if (converter == null) {
            throw new UnsupportedFormatException("No suitable converter found for: " + originalName);
        }
        
        log.debug("Using converter: {}", converter.getClass().getSimpleName());
        return converter.convert(bytes);
    }

    /**
     * Finds the first converter that supports the given filename.
     * 
     * @param filenameOrMime the filename or MIME type
     * @return the suitable converter or null if none found
     */
    private DocumentConverter findConverter(String filenameOrMime) {
        return converters.stream()
                .filter(converter -> converter.supports(filenameOrMime))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if any converter supports the given filename or MIME type.
     * 
     * @param filenameOrMime the filename or MIME type to check
     * @return true if a suitable converter exists
     */
    public boolean isSupported(String filenameOrMime) {
        return findConverter(filenameOrMime) != null;
    }
}
