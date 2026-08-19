package uk.gegc.quizmaker.features.documentProcess.application;

import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

/** Prepares one bounded upload for owner-scoped transactional publication. */
public interface NormalizedDocumentFilePreparationService {
    NormalizedDocument prepare(String admissionOwnerKey, String originalName, MultipartFile file);
}
