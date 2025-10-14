package uk.gegc.quizmaker.features.quiz.domain.model.export;

import java.io.InputStream;
import java.util.function.Supplier;

/**
 * Represents an exported file ready for download.
 * Uses a supplier for lazy content generation to support streaming.
 */
public record ExportFile(
    String filename,
    String contentType,
    Supplier<InputStream> contentSupplier,
    long contentLength
) {
    public ExportFile {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be null or blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Content type cannot be null or blank");
        }
        if (contentSupplier == null) {
            throw new IllegalArgumentException("Content supplier cannot be null");
        }
        if (contentLength < 0) {
            contentLength = -1; // Unknown length
        }
    }
}

