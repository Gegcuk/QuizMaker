package uk.gegc.quizmaker.service.document;

import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;

public interface DocumentValidationService {

    /**
     * Validate file upload parameters
     */
    void validateFileUpload(MultipartFile file, String chunkingStrategy, Integer maxChunkSize);

    /**
     * Validate reprocess document request
     */
    void validateReprocessRequest(ProcessDocumentRequest request);

    /**
     * Check if file type is supported
     */
    boolean isSupportedFileType(String contentType);
} 