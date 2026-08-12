package uk.gegc.quizmaker.features.document.application;

import java.nio.file.Path;
import java.util.Objects;

/** Server-owned input descriptor for one isolated document conversion. */
public record DocumentParseRequest(
        Path sourcePath,
        String originalFilename,
        String contentType,
        long sizeBytes
) {

    public DocumentParseRequest {
        Objects.requireNonNull(sourcePath, "Document parse source is required");
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Document parse filename is required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Document parse content type is required");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Document parse size must be positive");
        }
        sourcePath = sourcePath.toAbsolutePath().normalize();
    }

    public static DocumentParseRequest from(StagedDocumentUpload upload) {
        Objects.requireNonNull(upload, "Staged document upload is required");
        return new DocumentParseRequest(
                upload.stagingPath(),
                upload.originalFilename(),
                upload.detectedContentType(),
                upload.sizeBytes()
        );
    }
}
