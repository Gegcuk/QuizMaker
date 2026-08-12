package uk.gegc.quizmaker.features.document.application;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Stages untrusted uploads with bounded streaming and validates the type from
 * content before a converter or persistent document record is created.
 */
public interface DocumentUploadStagingService {

    StagedDocumentUpload stage(MultipartFile file);

    StagedDocumentUpload stage(InputStream source, String originalFilename, String declaredContentType, long declaredSize);

    Path promote(StagedDocumentUpload upload);

    /**
     * Removes an owned storage path idempotently.
     *
     * @return {@code true} when the path is absent or removed, {@code false} when cleanup is deferred
     */
    boolean discard(Path path);

    /**
     * Visits published regular files whose retention period has elapsed.
     * Implementations must stream candidates without materializing the full
     * storage listing and provide absolute normalized paths. The caller owns
     * reference checks and deletion decisions.
     */
    void visitExpiredPublishedFiles(Consumer<Path> visitor);
}
