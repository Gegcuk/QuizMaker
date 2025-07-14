package uk.gegc.quizmaker.service.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.model.user.User;

import java.util.List;
import java.util.UUID;

public interface DocumentProcessingService {

    /**
     * Upload and process a document file
     */
    DocumentDto uploadAndProcessDocument(String username, byte[] fileContent, String filename, 
                                       ProcessDocumentRequest request);

    /**
     * Get document by ID
     */
    DocumentDto getDocumentById(UUID documentId);

    /**
     * Get all documents for a user
     */
    Page<DocumentDto> getUserDocuments(String username, Pageable pageable);

    /**
     * Get chunks for a specific document
     */
    List<DocumentChunkDto> getDocumentChunks(UUID documentId);

    /**
     * Get a specific chunk by document ID and chunk index
     */
    DocumentChunkDto getDocumentChunk(UUID documentId, Integer chunkIndex);

    /**
     * Delete a document and all its chunks
     */
    void deleteDocument(String username, UUID documentId);

    /**
     * Reprocess a document with new settings
     */
    DocumentDto reprocessDocument(String username, UUID documentId, ProcessDocumentRequest request);

    /**
     * Get processing status of a document
     */
    DocumentDto getDocumentStatus(UUID documentId);
} 