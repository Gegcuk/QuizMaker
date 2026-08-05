package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.DocumentValidationService;
import uk.gegc.quizmaker.shared.exception.DocumentUploadLimitExceededException;

@Service
@RequiredArgsConstructor
public class DocumentValidationServiceImpl implements DocumentValidationService {

    private static final int MIN_CHUNK_SIZE = 100;
    private static final int MAX_CHUNK_SIZE = 100000;

    private final DocumentProcessingLimits limits;

    @Override
    public void validateFileUpload(MultipartFile file, String chunkingStrategy, Integer maxChunkSize) {
        // Validate file is provided
        if (file == null) {
            throw new IllegalArgumentException("No file provided");
        }

        // Validate file is not empty
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        // Reject based on metadata before opening a stream. The staging service then
        // enforces the same cap while copying because multipart metadata is untrusted.
        if (file.getSize() > limits.getMaxUploadBytes()) {
            throw new DocumentUploadLimitExceededException();
        }

        // Validate chunk size if provided
        if (maxChunkSize != null && (maxChunkSize < MIN_CHUNK_SIZE || maxChunkSize > MAX_CHUNK_SIZE)) {
            throw new IllegalArgumentException("Invalid chunk size: must be between " + MIN_CHUNK_SIZE + " and " + MAX_CHUNK_SIZE);
        }

        // Validate chunking strategy if provided
        if (chunkingStrategy != null) {
            try {
                ProcessDocumentRequest.ChunkingStrategy.valueOf(chunkingStrategy.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid chunking strategy: " + chunkingStrategy);
            }
        }
    }

    @Override
    public void validateReprocessRequest(ProcessDocumentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        if (request.getMaxChunkSize() != null &&
                (request.getMaxChunkSize() < MIN_CHUNK_SIZE || request.getMaxChunkSize() > MAX_CHUNK_SIZE)) {
            throw new IllegalArgumentException("Invalid chunk size: must be between " + MIN_CHUNK_SIZE + " and " + MAX_CHUNK_SIZE);
        }

        if (request.getChunkingStrategy() == null) {
            throw new IllegalArgumentException("Chunking strategy cannot be null");
        }
    }

    @Override
    public boolean isSupportedFileType(String contentType) {
        return contentType != null && (
                contentType.equals("application/pdf") ||
                        contentType.equals("application/epub+zip") ||
                        contentType.equals("text/plain")
        );
    }
}
