package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.api.dto.DocumentChunkDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentChunkingService;
import uk.gegc.quizmaker.features.document.application.DocumentDeletionService;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
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
import uk.gegc.quizmaker.shared.exception.UserNotAuthorizedException;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service("documentProcessingService")
@RequiredArgsConstructor
public class DocumentProcessingServiceImpl implements DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final UserRepository userRepository;
    private final DocumentMapper documentMapper;
    private final DocumentChunkingService documentChunkingService;
    private final DocumentUploadStagingService uploadStagingService;
    private final DocumentParseExecutor documentParseExecutor;
    private final DocumentProcessingLimits limits;
    private final TransactionTemplate transactionTemplate;
    private final DocumentDeletionService documentDeletionService;

    @Override
    public DocumentDto uploadAndProcessDocument(String username, byte[] fileContent, String filename,
                                                ProcessDocumentRequest request) {
        if (fileContent == null) {
            throw new IllegalArgumentException("File content is required");
        }
        StagedDocumentUpload upload = uploadStagingService.stage(
                new ByteArrayInputStream(fileContent), filename, null, fileContent.length);
        return processStagedUpload(username, upload, request);
    }

    @Override
    public DocumentDto uploadAndProcessDocument(String username, MultipartFile file,
                                                ProcessDocumentRequest request) {
        return processStagedUpload(username, uploadStagingService.stage(file), request);
    }

    private DocumentDto processStagedUpload(String username, StagedDocumentUpload upload,
                                            ProcessDocumentRequest request) {
        Path publishedPath = null;
        Document document;
        try {
            ConvertedDocument convertedDocument = convertWithinLimits(username, upload);
            List<UniversalChunker.Chunk> chunks = documentChunkingService.chunkDocument(convertedDocument, request);
            boolean storeChunks = shouldStoreChunks(request);
            publishedPath = uploadStagingService.promote(upload);
            Path finalPublishedPath = publishedPath;
            document = transactionTemplate.execute(status -> publishNewDocument(
                    username, upload, finalPublishedPath, convertedDocument, chunks, storeChunks));
        } catch (RuntimeException e) {
            uploadStagingService.discard(publishedPath);
            throw e;
        } finally {
            uploadStagingService.discard(upload.stagingPath());
        }
        return documentMapper.toDto(document);
    }

    private ConvertedDocument convertWithinLimits(String username, StagedDocumentUpload upload) {
        ConvertedDocument convertedDocument = documentParseExecutor.execute(
                username, DocumentParseRequest.from(upload));
        if (convertedDocument.getFullContent() == null || convertedDocument.getFullContent().isBlank()) {
            throw new DocumentProcessingException("Document contains no extractable text");
        }
        if (convertedDocument.getFullContent().length() > limits.getMaxExtractedCharacters()) {
            throw new uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException(
                    "Extracted document text exceeds the configured limit");
        }
        return convertedDocument;
    }

    private Document publishNewDocument(
            String username,
            StagedDocumentUpload upload,
            Path publishedPath,
            ConvertedDocument convertedDocument,
            List<UniversalChunker.Chunk> chunks,
            boolean storeChunks
    ) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Document document = new Document();
        document.setOriginalFilename(upload.originalFilename());
        document.setContentType(upload.detectedContentType());
        document.setFileSize(upload.sizeBytes());
        document.setFilePath(publishedPath.toString());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());
        document.setUploadedBy(user);
        applyConvertedMetadata(document, convertedDocument, chunks.size());
        Document persistedDocument = documentRepository.save(document);

        if (storeChunks) {
            chunkRepository.saveAll(chunks.stream()
                    .map(chunk -> createDocumentChunk(persistedDocument, chunk))
                    .toList());
        }
        return persistedDocument;
    }

    private void applyConvertedMetadata(Document document, ConvertedDocument convertedDocument, int chunkCount) {
        document.setTitle(convertedDocument.getTitle());
        document.setAuthor(convertedDocument.getAuthor());
        document.setTotalPages(convertedDocument.getTotalPages());
        document.setTotalChunks(chunkCount);
        document.setProcessingError(null);
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
    public void deleteDocument(String username, UUID documentId) {
        documentDeletionService.deleteDocument(username, documentId);
    }

    @Override
    public DocumentDto reprocessDocument(String username, UUID documentId, ProcessDocumentRequest request) {
        // Parse first. Existing chunks remain available until the replacement is ready.
        Document document = getDocumentForReprocessing(username, documentId);
        Path source = Paths.get(document.getFilePath());
        ConvertedDocument convertedDocument = documentParseExecutor.execute(username, new DocumentParseRequest(
                source,
                document.getOriginalFilename(),
                document.getContentType(),
                document.getFileSize()
        ));
        if (convertedDocument.getFullContent() == null
                || convertedDocument.getFullContent().length() > limits.getMaxExtractedCharacters()) {
            throw new uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException(
                    "Extracted document text exceeds the configured limit");
        }
        List<UniversalChunker.Chunk> chunks = documentChunkingService.chunkDocument(convertedDocument, request);
        boolean storeChunks = shouldStoreChunks(request);
        Document updated = transactionTemplate.execute(status -> replaceDocumentChunks(
                username, documentId, convertedDocument, chunks, storeChunks));
        return documentMapper.toDto(updated);
    }

    private Document replaceDocumentChunks(
            String username,
            UUID documentId,
            ConvertedDocument convertedDocument,
            List<UniversalChunker.Chunk> chunks,
            boolean storeChunks
    ) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "reprocess");
        }

        chunkRepository.deleteByDocument(document);
        if (storeChunks) {
            chunkRepository.saveAll(chunks.stream()
                    .map(chunk -> createDocumentChunk(document, chunk))
                    .toList());
        }
        applyConvertedMetadata(document, convertedDocument, chunks.size());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setProcessedAt(LocalDateTime.now());
        return documentRepository.save(document);
    }

    private boolean shouldStoreChunks(ProcessDocumentRequest request) {
        return request.getStoreChunks() == null || request.getStoreChunks();
    }

    /**
     * Transactional database operation for getting document for reprocessing
     */
    @Transactional
    public Document getDocumentForReprocessing(String username, UUID documentId) {
        Document document = documentRepository.findByIdWithChunksAndUser(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "reprocess");
        }

        return document;
    }

    @Override
    public DocumentDto getDocumentStatus(UUID documentId, String username) {
        return getDocumentById(documentId, username);
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
