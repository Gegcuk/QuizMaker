package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.api.dto.DocumentChunkDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentChunkingService;
import uk.gegc.quizmaker.features.document.application.DocumentConversionService;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.document.infra.converter.UniversalChunker;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentMapper;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;
import uk.gegc.quizmaker.shared.exception.UserNotAuthorizedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service("documentProcessingService")
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingServiceImpl implements DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final UserRepository userRepository;
    private final DocumentMapper documentMapper;
    private final DocumentConversionService documentConversionService;
    private final DocumentChunkingService documentChunkingService;

    @Override
    public DocumentDto uploadAndProcessDocument(String username, byte[] fileContent, String filename,
                                                ProcessDocumentRequest request) {
        // Step 1: File operations (non-transactional)
        String filePath = saveFileToDisk(fileContent, filename);

        // Step 2: Create document entity (transactional)
        Document document = createDocumentEntity(username, filename, fileContent, filePath);

        // Step 3: Process document (non-transactional file operations, transactional DB operations)
        try {
            processDocument(document, fileContent, request);
        } catch (Exception e) {
            // Update document status on failure (transactional)
            updateDocumentStatusOnFailure(document, e.getMessage());
            throw new DocumentProcessingException("Failed to process document: " + e.getMessage(), e);
        }

        return documentMapper.toDto(document);
    }

    /**
     * Non-transactional file operations
     */
    private String saveFileToDisk(byte[] fileContent, String filename) {
        try {
            Path uploadDir = Paths.get("uploads/documents");
            Files.createDirectories(uploadDir);

            String uniqueFilename = UUID.randomUUID() + "_" + filename;
            Path filePath = uploadDir.resolve(uniqueFilename);
            Files.write(filePath, fileContent);

            return filePath.toString();
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to save file to disk: " + e.getMessage(), e);
        }
    }

    /**
     * Transactional database operation for creating document entity
     */
    @Transactional
    public Document createDocumentEntity(String username, String filename, byte[] fileContent, String filePath) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Document document = new Document();
        document.setOriginalFilename(filename);
        document.setContentType(detectContentType(filename));
        document.setFileSize((long) fileContent.length);
        document.setFilePath(filePath);
        document.setStatus(Document.DocumentStatus.UPLOADED);
        document.setUploadedAt(LocalDateTime.now());
        document.setUploadedBy(user);

        return documentRepository.save(document);
    }

    /**
     * Non-transactional document processing with separate transactional DB operations
     */
    private void processDocument(Document document, byte[] fileContent, ProcessDocumentRequest request) throws Exception {
        // Update status to processing (transactional)
        updateDocumentStatus(document, Document.DocumentStatus.PROCESSING);

        try {
            // Convert the document using the new converter system
            ConvertedDocument convertedDocument = documentConversionService.convertDocument(
                    fileContent, document.getOriginalFilename(), document.getContentType()
            );

            // Update document metadata (transactional)
            updateDocumentMetadata(document, convertedDocument);

            // Chunk the document using the new universal chunking system
            List<UniversalChunker.Chunk> chunks = documentChunkingService.chunkDocument(convertedDocument, request);

            // Store chunks if requested (transactional)
            if (request.getStoreChunks() != null && request.getStoreChunks()) {
                storeChunksTransactionally(document, chunks);
            }

            // Update final status (transactional)
            updateDocumentStatusToProcessed(document, chunks.size());

        } catch (Exception e) {
            // Update status on failure (transactional)
            updateDocumentStatusOnFailure(document, e.getMessage());
            throw e;
        }
    }

    /**
     * Transactional database operation for updating document status
     */
    @Transactional
    public void updateDocumentStatus(Document document, Document.DocumentStatus status) {
        document.setStatus(status);
        documentRepository.save(document);
    }

    /**
     * Transactional database operation for updating document metadata
     */
    @Transactional
    public void updateDocumentMetadata(Document document, ConvertedDocument convertedDocument) {
        document.setTitle(convertedDocument.getTitle());
        document.setAuthor(convertedDocument.getAuthor());
        document.setTotalPages(convertedDocument.getTotalPages());
        documentRepository.save(document);
    }

    /**
     * Transactional database operation for storing chunks with batch optimization
     */
    @Transactional
    public void storeChunksTransactionally(Document document, List<UniversalChunker.Chunk> chunks) {
        List<DocumentChunk> entities = chunks.stream()
                .map(chunk -> createDocumentChunk(document, chunk))
                .collect(Collectors.toList());

        chunkRepository.saveAll(entities);
    }

    /**
     * Helper method to create DocumentChunk entity from UniversalChunker.Chunk
     */
    private DocumentChunk createDocumentChunk(Document document, UniversalChunker.Chunk chunk) {
        DocumentChunk documentChunk = new DocumentChunk();
        documentChunk.setDocument(document);
        documentChunk.setChunkIndex(chunk.getChunkIndex());
        documentChunk.setTitle(chunk.getTitle());
        documentChunk.setContent(chunk.getContent());
        // Provide default values for page numbers if they are null
        documentChunk.setStartPage(chunk.getStartPage() != null ? chunk.getStartPage() : 1);
        documentChunk.setEndPage(chunk.getEndPage() != null ? chunk.getEndPage() : 1);
        documentChunk.setWordCount(chunk.getWordCount());
        documentChunk.setCharacterCount(chunk.getCharacterCount());
        documentChunk.setCreatedAt(LocalDateTime.now());
        documentChunk.setChapterTitle(chunk.getChapterTitle());
        documentChunk.setChapterNumber(chunk.getChapterNumber());
        documentChunk.setSectionTitle(chunk.getSectionTitle());
        documentChunk.setSectionNumber(chunk.getSectionNumber());
        documentChunk.setChunkType(mapChunkType(chunk.getChunkType()));

        return documentChunk;
    }

    /**
     * Transactional database operation for updating document to processed status
     */
    @Transactional
    public void updateDocumentStatusToProcessed(Document document, int totalChunks) {
        document.setTotalChunks(totalChunks);
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setProcessedAt(LocalDateTime.now());
        documentRepository.save(document);
    }

    /**
     * Transactional database operation for updating document status on failure
     */
    @Transactional
    public void updateDocumentStatusOnFailure(Document document, String errorMessage) {
        document.setStatus(Document.DocumentStatus.FAILED);
        document.setProcessingError(errorMessage);
        document.setProcessedAt(LocalDateTime.now());
        documentRepository.save(document);
    }

    @Override
    public DocumentDto getDocumentById(UUID documentId, String username) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "access");
        }

        return documentMapper.toDto(document);
    }

    @Override
    public Page<DocumentDto> getUserDocuments(String username, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Page<Document> documents = documentRepository.findByUploadedBy(user, pageable);
        return documents.map(documentMapper::toDto);
    }

    @Override
    public List<DocumentChunkDto> getDocumentChunks(UUID documentId, String username) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "access chunks of");
        }

        List<DocumentChunk> chunks = chunkRepository.findByDocumentOrderByChunkIndex(document);
        return chunks.stream().map(documentMapper::toChunkDto).toList();
    }

    @Override
    public DocumentChunkDto getDocumentChunk(UUID documentId, Integer chunkIndex, String username) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "access chunks of");
        }

        DocumentChunk chunk = chunkRepository.findByDocumentIdAndChunkIndex(documentId, chunkIndex);
        if (chunk == null) {
            throw new RuntimeException("Chunk not found: " + documentId + ":" + chunkIndex);
        }
        return documentMapper.toChunkDto(chunk);
    }

    @Override
    @Transactional
    public void deleteDocument(String username, UUID documentId) {
        Document document = getDocumentForDeletion(username, documentId);

        // Delete file from disk (non-transactional, but safe within transaction)
        deleteFileFromDisk(document.getFilePath());

        // Delete from database (requires active transaction)
        deleteDocumentFromDatabase(document);
    }

    /**
     * Transactional database operation for getting document for deletion
     */
    @Transactional
    public Document getDocumentForDeletion(String username, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "delete");
        }

        return document;
    }

    /**
     * Non-transactional file deletion operation
     */
    private void deleteFileFromDisk(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            log.warn("Failed to delete file from disk: {}", filePath, e);
            // Don't throw exception for file deletion failures
        }
    }

    /**
     * Transactional database operation for deleting document
     */
    @Transactional
    public void deleteDocumentFromDatabase(Document document) {
        chunkRepository.deleteByDocument(document);
        documentRepository.delete(document);
    }

    @Override
    public DocumentDto reprocessDocument(String username, UUID documentId, ProcessDocumentRequest request) {
        // Step 1: Get document and validate authorization (transactional)
        Document document = getDocumentForReprocessing(username, documentId);

        // Step 2: Read file content (non-transactional)
        byte[] fileContent = readFileContent(document.getFilePath());

        // Step 3: Delete existing chunks (transactional)
        deleteExistingChunks(document);

        // Step 4: Reprocess document (non-transactional processing, transactional DB operations)
        try {
            processDocument(document, fileContent, request);
        } catch (Exception e) {
            updateDocumentStatusOnFailure(document, e.getMessage());
            throw new DocumentProcessingException("Failed to reprocess document: " + e.getMessage(), e);
        }

        return documentMapper.toDto(document);
    }

    /**
     * Transactional database operation for getting document for reprocessing
     */
    @Transactional
    public Document getDocumentForReprocessing(String username, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "reprocess");
        }

        return document;
    }

    /**
     * Non-transactional file reading operation
     */
    private byte[] readFileContent(String filePath) {
        try {
            return Files.readAllBytes(Paths.get(filePath));
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to read document file: " + e.getMessage(), e);
        }
    }

    /**
     * Transactional database operation for deleting existing chunks
     */
    @Transactional
    public void deleteExistingChunks(Document document) {
        chunkRepository.deleteByDocument(document);
    }

    @Override
    public DocumentDto getDocumentStatus(UUID documentId, String username) {
        return getDocumentById(documentId, username);
    }

    // Helper methods (unchanged)
    private String detectContentType(String filename) {
        if (filename.toLowerCase().endsWith(".pdf")) {
            return "application/pdf";
        } else if (filename.toLowerCase().endsWith(".txt")) {
            return "text/plain";
        } else if (filename.toLowerCase().endsWith(".epub")) {
            return "application/epub+zip";
        }
        return "application/octet-stream";
    }

    private DocumentChunk.ChunkType mapChunkType(ProcessDocumentRequest.ChunkingStrategy strategy) {
        return switch (strategy) {
            case CHAPTER_BASED -> DocumentChunk.ChunkType.CHAPTER;
            case SECTION_BASED -> DocumentChunk.ChunkType.SECTION;
            case PAGE_BASED -> DocumentChunk.ChunkType.PAGE_BASED;
            case SIZE_BASED, AUTO -> DocumentChunk.ChunkType.SIZE_BASED;
        };
    }
} 