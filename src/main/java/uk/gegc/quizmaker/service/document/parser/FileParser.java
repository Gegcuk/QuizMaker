package uk.gegc.quizmaker.service.document.parser;

import java.io.InputStream;
import java.util.List;

public interface FileParser {

    /**
     * Check if this parser can handle the given file type
     */
    boolean canParse(String contentType, String filename);

    /**
     * Parse the file and extract structured content
     */
    ParsedDocument parse(InputStream inputStream, String filename) throws Exception;

    /**
     * Get supported content types
     */
    List<String> getSupportedContentTypes();

    /**
     * Get supported file extensions
     */
    List<String> getSupportedExtensions();
} 