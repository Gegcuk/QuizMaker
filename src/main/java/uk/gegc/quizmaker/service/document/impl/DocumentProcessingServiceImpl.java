package uk.gegc.quizmaker.service.document.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.mapper.document.DocumentMapper;
import uk.gegc.quizmaker.model.document.Document;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.document.DocumentChunkRepository;
import uk.gegc.quizmaker.repository.document.DocumentRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.document.DocumentProcessingService;
import uk.gegc.quizmaker.service.document.chunker.ContentChunker;
import uk.gegc.quizmaker.service.document.chunker.ContentChunker.Chunk;
import uk.gegc.quizmaker.service.document.parser.FileParser;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;
import uk.gegc.quizmaker.exception.DocumentStorageException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingServiceImpl implements DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final UserRepository userRepository;
    private final DocumentMapper documentMapper;
    private final List<FileParser> fileParsers;
    private final List<ContentChunker> contentChunkers;

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
            throw new RuntimeException("Failed to process document: " + e.getMessage(), e);
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
            // Parse the document (non-transactional file operation)
            FileParser parser = findParser(document.getContentType(), document.getOriginalFilename());
            ParsedDocument parsedDocument = parser.parse(new ByteArrayInputStream(fileContent), 
                    document.getOriginalFilename());

            // Update document metadata (transactional)
            updateDocumentMetadata(document, parsedDocument);

            // Chunk the document (non-transactional processing)
            ContentChunker chunker = findChunker(request.getChunkingStrategy());
            List<Chunk> chunks = chunker.chunkDocument(parsedDocument, request);

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
    public void updateDocumentMetadata(Document document, ParsedDocument parsedDocument) {
        document.setTitle(parsedDocument.getTitle());
        document.setAuthor(parsedDocument.getAuthor());
        document.setTotalPages(parsedDocument.getTotalPages());
        documentRepository.save(document);
    }

    /**
     * Transactional database operation for storing chunks
     */
    @Transactional
    public void storeChunksTransactionally(Document document, List<Chunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            DocumentChunk documentChunk = new DocumentChunk();
            documentChunk.setDocument(document);
            documentChunk.setChunkIndex(i);
            documentChunk.setTitle(chunk.getTitle());
            documentChunk.setContent(chunk.getContent());
            documentChunk.setStartPage(chunk.getStartPage());
            documentChunk.setEndPage(chunk.getEndPage());
            documentChunk.setWordCount(chunk.getWordCount());
            documentChunk.setCharacterCount(chunk.getCharacterCount());
            documentChunk.setCreatedAt(LocalDateTime.now());
            documentChunk.setChapterTitle(chunk.getChapterTitle());
            documentChunk.setChapterNumber(chunk.getChapterNumber());
            documentChunk.setSectionTitle(chunk.getSectionTitle());
            documentChunk.setSectionNumber(chunk.getSectionNumber());
            documentChunk.setChunkType(mapChunkType(chunk.getChunkType()));
            
            chunkRepository.save(documentChunk);
        }
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
        documentRepository.save(document);
    }

    @Override
    public DocumentDto getDocumentById(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
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
    public List<DocumentChunkDto> getDocumentChunks(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        List<DocumentChunk> chunks = chunkRepository.findByDocumentOrderByChunkIndex(document);
        return chunks.stream().map(documentMapper::toChunkDto).toList();
    }

    @Override
    public DocumentChunkDto getDocumentChunk(UUID documentId, Integer chunkIndex) {
        DocumentChunk chunk = chunkRepository.findByDocumentIdAndChunkIndex(documentId, chunkIndex);
        if (chunk == null) {
            throw new RuntimeException("Chunk not found: " + documentId + ":" + chunkIndex);
        }
        return documentMapper.toChunkDto(chunk);
    }

    @Override
    public void deleteDocument(String username, UUID documentId) {
        // Step 1: Get document and validate authorization (transactional)
        Document document = getDocumentForDeletion(username, documentId);
        
        // Step 2: Delete file from disk (non-transactional)
        deleteFileFromDisk(document.getFilePath());
        
        // Step 3: Delete from database (transactional)
        deleteDocumentFromDatabase(document);
    }

    /**
     * Transactional database operation for getting document for deletion
     */
    @Transactional
    public Document getDocumentForDeletion(String username, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        
        if (!document.getUploadedBy().equals(user)) {
            throw new RuntimeException("User not authorized to delete this document");
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
        }
    }

    /**
     * Transactional database operation for deleting document
     */
    @Transactional
    public void deleteDocumentFromDatabase(Document document) {
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
            throw new RuntimeException("Failed to reprocess document: " + e.getMessage(), e);
        }
        
        return documentMapper.toDto(document);
    }

    /**
     * Transactional database operation for getting document for reprocessing
     */
    @Transactional
    public Document getDocumentForReprocessing(String username, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        
        if (!document.getUploadedBy().equals(user)) {
            throw new RuntimeException("User not authorized to reprocess this document");
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
    public DocumentDto getDocumentStatus(UUID documentId) {
        return getDocumentById(documentId);
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

    private FileParser findParser(String contentType, String filename) {
        for (FileParser parser : fileParsers) {
            if (parser.canParse(contentType, filename)) {
                return parser;
            }
        }
        throw new RuntimeException("No parser found for content type: " + contentType);
    }

    private ContentChunker findChunker(ProcessDocumentRequest.ChunkingStrategy strategy) {
        for (ContentChunker chunker : contentChunkers) {
            if (chunker.getSupportedStrategy() == strategy) {
                return chunker;
            }
        }
        throw new RuntimeException("No chunker found for strategy: " + strategy);
    }

    private DocumentChunk.ChunkType mapChunkType(ProcessDocumentRequest.ChunkingStrategy strategy) {
        switch (strategy) {
            case CHAPTER_BASED:
                return DocumentChunk.ChunkType.CHAPTER;
            case SECTION_BASED:
                return DocumentChunk.ChunkType.SECTION;
            case PAGE_BASED:
                return DocumentChunk.ChunkType.PAGE_BASED;
            case SIZE_BASED:
            default:
                return DocumentChunk.ChunkType.SIZE_BASED;
        }
    }
} 