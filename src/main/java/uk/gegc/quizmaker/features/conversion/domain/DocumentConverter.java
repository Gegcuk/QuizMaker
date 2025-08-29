package uk.gegc.quizmaker.features.conversion.domain;

/**
 * Interface for converting document bytes to text.
 * Strategy pattern for supporting different file formats.
 */
public interface DocumentConverter {
    
    /**
     * Checks if this converter supports the given filename or MIME type.
     * 
     * @param filenameOrMime the filename or MIME type to check
     * @return true if this converter can handle the format
     */
    boolean supports(String filenameOrMime);
    
    /**
     * Converts document bytes to plain text.
     * 
     * @param bytes the document bytes
     * @return ConversionResult containing the extracted text
     * @throws ConversionException if conversion fails
     */
    ConversionResult convert(byte[] bytes) throws ConversionException;
}
