package uk.gegc.quizmaker.features.document.application;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Stages untrusted uploads with bounded streaming and validates the type from
 * content before a converter or persistent document record is created.
 */
public interface DocumentUploadStagingService {

    StagedDocumentUpload stage(MultipartFile file);

    StagedDocumentUpload stage(InputStream source, String originalFilename, String declaredContentType, long declaredSize);

    Path promote(StagedDocumentUpload upload);

    void discard(Path path);

    void reconcile(Collection<String> referencedFilePaths);
}
