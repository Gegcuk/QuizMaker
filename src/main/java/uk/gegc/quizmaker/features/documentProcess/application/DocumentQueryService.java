package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Service for safe read-only access to documents and text slicing.
 */
@Service("documentProcessQueryService")
@RequiredArgsConstructor
@Slf4j
public class DocumentQueryService {

    private final NormalizedDocumentRepository documentRepository;

    /**
     * Retrieves a document by ID.
     * 
     * @param documentId the document ID
     * @return the document entity
     * @throws ResourceNotFoundException if document not found
     */
    @Transactional(readOnly = true)
    public NormalizedDocument getDocument(UUID documentId) {
        return documentRepository.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
    }

    /**
     * Extracts a text slice from a document with bounds checking.
     * 
     * @param documentId the document ID
     * @param start the start offset (inclusive)
     * @param end the end offset (exclusive)
     * @return the text slice
     * @throws ResourceNotFoundException if document not found
     * @throws IllegalArgumentException if bounds are invalid
     */
    @Transactional(readOnly = true)
    public String getTextSlice(UUID documentId, int start, int end) {
        NormalizedDocument document = getDocument(documentId);
        
        if (document.getNormalizedText() == null) {
            throw new IllegalStateException("Document has no normalized text: " + documentId);
        }
        
        String text = document.getNormalizedText();
        int textLength = text.length();
        
        // Validate bounds
        if (start < 0) {
            throw new ValidationErrorException("Start offset cannot be negative: " + start);
        }
        if (end < start) {
            throw new ValidationErrorException("End offset must be greater than or equal to start: end=" + end + ", start=" + start);
        }
        if (start > textLength) {
            throw new ValidationErrorException("Start offset exceeds text length: start=" + start + ", length=" + textLength);
        }
        
        // Adjust end to text length if it exceeds
        int actualEnd = Math.min(end, textLength);
        
        log.debug("Extracting text slice: document={}, start={}, end={}, actualEnd={}", 
                 documentId, start, end, actualEnd);
        
        return text.substring(start, actualEnd);
    }

    /**
     * Gets the text length of a document without loading the full text.
     * 
     * @param documentId the document ID
     * @return the character count
     * @throws ResourceNotFoundException if document not found
     */
    @Transactional(readOnly = true)
    public int getTextLength(UUID documentId) {
        Integer len = documentRepository.findCharCountById(documentId);
        if (len == null) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }
        return len;
    }

    /**
     * Gets the full normalized text of a document.
     * 
     * @param documentId the document ID
     * @return the full normalized text
     * @throws ResourceNotFoundException if document not found
     */
    @Transactional(readOnly = true)
    public String getFullText(UUID documentId) {
        NormalizedDocument document = getDocument(documentId);
        
        if (document.getNormalizedText() == null) {
            throw new IllegalStateException("Document has no normalized text: " + documentId);
        }
        
        return document.getNormalizedText();
    }
}
