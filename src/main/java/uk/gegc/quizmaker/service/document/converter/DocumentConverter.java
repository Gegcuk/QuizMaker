package uk.gegc.quizmaker.service.document.converter;

import java.io.InputStream;
import java.util.List;

/**
 * Interface for document converters that convert different input formats
 * to a standardized ConvertedDocument format.
 * 
 * This follows the Strategy pattern, allowing different converters
 * to be implemented for different file formats while maintaining
 * a consistent interface.
 */
public interface DocumentConverter {

    /**
     * Check if this converter can handle the given file type
     */
    boolean canConvert(String contentType, String filename);

    /**
     * Convert the input file to the standardized ConvertedDocument format
     */
    ConvertedDocument convert(InputStream inputStream, String filename, Long fileSize) throws Exception;

    /**
     * Get supported content types for this converter
     */
    List<String> getSupportedContentTypes();

    /**
     * Get supported file extensions for this converter
     */
    List<String> getSupportedExtensions();

    /**
     * Get the converter type/name for identification
     */
    String getConverterType();
} 