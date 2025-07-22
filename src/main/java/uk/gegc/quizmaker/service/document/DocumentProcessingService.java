package uk.gegc.quizmaker.service.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;

import java.util.List;
import java.util.UUID;

public interface DocumentProcessingService {

    /**
     * Upload and process a document file
     */
    DocumentDto uploadAndProcessDocument(String username, byte[] fileContent, String filename,
                                         ProcessDocumentRequest request);

    /**
     * Get document by ID with authorization check
     */
    DocumentDto getDocumentById(UUID documentId, String username);

    /**
     * Get all documents for a user
     */
    Page<DocumentDto> getUserDocuments(String username, Pageable pageable);

    /**
     * Get chunks for a specific document with authorization check
     */
    List<DocumentChunkDto> getDocumentChunks(UUID documentId, String username);

    /**
     * Get a specific chunk by document ID and chunk index with authorization check
     */
    DocumentChunkDto getDocumentChunk(UUID documentId, Integer chunkIndex, String username);

    /**
     * Delete a document and all its chunks
     */
    void deleteDocument(String username, UUID documentId);

    /**
     * Reprocess a document with new settings
     */
    DocumentDto reprocessDocument(String username, UUID documentId, ProcessDocumentRequest request);

    /**
     * Get processing status of a document with authorization check
     */
    DocumentDto getDocumentStatus(UUID documentId, String username);
} 