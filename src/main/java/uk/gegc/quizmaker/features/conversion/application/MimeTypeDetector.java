package uk.gegc.quizmaker.features.conversion.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lightweight MIME type detector based on file extensions.
 * Used to determine content types for uploaded files.
 */
@Component
@Slf4j
public class MimeTypeDetector {

    private static final Map<String, String> EXTENSION_TO_MIME = Map.of(
        ".txt", "text/plain",
        ".pdf", "application/pdf",
        ".html", "text/html",
        ".htm", "text/html",
        ".epub", "application/epub+zip",
        ".srt", "text/srt",
        ".vtt", "text/vtt"
    );

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /**
     * Detects MIME type based on filename extension.
     * 
     * @param filename the filename to analyze
     * @return the detected MIME type or default if unknown
     */
    public String detectMimeType(String filename) {
        if (filename == null || filename.isEmpty()) {
            return DEFAULT_MIME_TYPE;
        }

        String lowerFilename = filename.toLowerCase();
        
        for (Map.Entry<String, String> entry : EXTENSION_TO_MIME.entrySet()) {
            if (lowerFilename.endsWith(entry.getKey())) {
                String mimeType = entry.getValue();
                log.debug("Detected MIME type for {}: {}", filename, mimeType);
                return mimeType;
            }
        }

        log.debug("Unknown file extension for {}, using default MIME type: {}", filename, DEFAULT_MIME_TYPE);
        return DEFAULT_MIME_TYPE;
    }

    /**
     * Checks if a MIME type is supported by any converter.
     * 
     * @param mimeType the MIME type to check
     * @return true if the MIME type is supported
     */
    public boolean isSupportedMimeType(String mimeType) {
        if (mimeType == null) return false;
        return EXTENSION_TO_MIME.containsValue(mimeType.toLowerCase());
    }

    /**
     * Gets the file extension for a given MIME type.
     * 
     * @param mimeType the MIME type
     * @return the file extension or null if not found
     */
    public String getExtensionForMimeType(String mimeType) {
        if (mimeType == null) return null;
        
        return EXTENSION_TO_MIME.entrySet().stream()
            .filter(entry -> entry.getValue().equals(mimeType.toLowerCase()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }
}
