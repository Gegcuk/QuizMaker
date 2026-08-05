package uk.gegc.quizmaker.features.document.application;

import java.nio.file.Path;

/**
 * A validated, server-owned temporary upload. The original client filename is
 * retained only as document metadata; storage paths never derive from it.
 */
public record StagedDocumentUpload(
        Path stagingPath,
        String originalFilename,
        String detectedContentType,
        long sizeBytes
) {
}
