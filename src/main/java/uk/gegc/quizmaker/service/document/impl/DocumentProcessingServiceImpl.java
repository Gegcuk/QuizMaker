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
    @Transactional
    public DocumentDto uploadAndProcessDocument(String username, byte[] fileContent, String filename, 
                                             ProcessDocumentRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        // Save file to disk
        String filePath = saveFileToDisk(fileContent, filename);
        
        // Create document entity
        Document document = new Document();
        document.setOriginalFilename(filename);
        document.setContentType(detectContentType(filename));
        document.setFileSize((long) fileContent.length);
        document.setFilePath(filePath);
        document.setStatus(Document.DocumentStatus.UPLOADED);
        document.setUploadedAt(LocalDateTime.now());
        document.setUploadedBy(user);
        
        document = documentRepository.save(document);

        try {
                    // Process the document
        try {
            processDocument(document, fileContent, request);
        } catch (Exception e) {
            document.setStatus(Document.DocumentStatus.FAILED);
            document.setProcessingError(e.getMessage());
            documentRepository.save(document);
            throw new RuntimeException("Failed to process document: " + e.getMessage(), e);
        }
            
            return documentMapper.toDto(document);
        } catch (Exception e) {
            log.error("Error processing document: {}", filename, e);
            document.setStatus(Document.DocumentStatus.FAILED);
            document.setProcessingError(e.getMessage());
            documentRepository.save(document);
            throw new RuntimeException("Failed to process document: " + e.getMessage(), e);
        }
    }

    private void processDocument(Document document, byte[] fileContent, ProcessDocumentRequest request) throws Exception {
        document.setStatus(Document.DocumentStatus.PROCESSING);
        documentRepository.save(document);

        try {
            // Parse the document
            FileParser parser = findParser(document.getContentType(), document.getOriginalFilename());
            ParsedDocument parsedDocument = parser.parse(new ByteArrayInputStream(fileContent), 
                    document.getOriginalFilename());

            // Update document metadata
            document.setTitle(parsedDocument.getTitle());
            document.setAuthor(parsedDocument.getAuthor());
            document.setTotalPages(parsedDocument.getTotalPages());

            // Chunk the document
            ContentChunker chunker = findChunker(request.getChunkingStrategy());
            List<Chunk> chunks = chunker.chunkDocument(parsedDocument, request);

            // Store chunks if requested
            if (request.getStoreChunks() != null && request.getStoreChunks()) {
                storeChunks(document, chunks);
            }

            document.setTotalChunks(chunks.size());
            document.setStatus(Document.DocumentStatus.PROCESSED);
            document.setProcessedAt(LocalDateTime.now());
            documentRepository.save(document);

        } catch (Exception e) {
            document.setStatus(Document.DocumentStatus.FAILED);
            document.setProcessingError(e.getMessage());
            documentRepository.save(document);
            throw e;
        }
    }

    private FileParser findParser(String contentType, String filename) {
        log.debug("Finding parser for content type: {} and filename: {}", contentType, filename);
        log.debug("Available parsers: {}", fileParsers.size());
        
        return fileParsers.stream()
                .filter(parser -> {
                    boolean canParse = parser.canParse(contentType, filename);
                    log.debug("Parser {} can parse: {}", parser.getClass().getSimpleName(), canParse);
                    return canParse;
                })
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No parser found for content type: " + contentType));
    }

    private ContentChunker findChunker(ProcessDocumentRequest.ChunkingStrategy strategy) {
        final ProcessDocumentRequest.ChunkingStrategy finalStrategy = 
            (strategy == ProcessDocumentRequest.ChunkingStrategy.AUTO) 
                ? ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED 
                : strategy;
        
        return contentChunkers.stream()
                .filter(chunker -> chunker.getSupportedStrategy() == finalStrategy)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No chunker found for strategy: " + finalStrategy));
    }

    private void storeChunks(Document document, List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            DocumentChunk documentChunk = new DocumentChunk();
            documentChunk.setDocument(document);
            documentChunk.setChunkIndex(chunk.getChunkIndex());
            documentChunk.setTitle(chunk.getTitle());
            documentChunk.setContent(chunk.getContent());
            documentChunk.setStartPage(chunk.getStartPage());
            documentChunk.setEndPage(chunk.getEndPage());
            documentChunk.setWordCount(chunk.getWordCount());
            documentChunk.setCharacterCount(chunk.getCharacterCount());
            documentChunk.setCreatedAt(LocalDateTime.now());
            documentChunk.setChapterTitle(chunk.getChapterTitle());
            documentChunk.setSectionTitle(chunk.getSectionTitle());
            documentChunk.setChapterNumber(chunk.getChapterNumber());
            documentChunk.setSectionNumber(chunk.getSectionNumber());
            documentChunk.setChunkType(mapChunkType(chunk.getChunkType()));
            
            chunkRepository.save(documentChunk);
        }
    }

    private DocumentChunk.ChunkType mapChunkType(ProcessDocumentRequest.ChunkingStrategy strategy) {
        if (strategy == null) {
            return DocumentChunk.ChunkType.SIZE_BASED;
        }
        
        switch (strategy) {
            case CHAPTER_BASED:
                return DocumentChunk.ChunkType.CHAPTER;
            case SECTION_BASED:
                return DocumentChunk.ChunkType.SECTION;
            case SIZE_BASED:
                return DocumentChunk.ChunkType.SIZE_BASED;
            case PAGE_BASED:
                return DocumentChunk.ChunkType.PAGE_BASED;
            default:
                return DocumentChunk.ChunkType.SIZE_BASED;
        }
    }

    private String saveFileToDisk(byte[] fileContent, String filename) {
        try {
            String uploadDir = "uploads/documents";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String uniqueFilename = UUID.randomUUID().toString() + "_" + filename;
            Path filePath = uploadPath.resolve(uniqueFilename);
            Files.write(filePath, fileContent);
            
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save file: " + e.getMessage(), e);
        }
    }

    private String detectContentType(String filename) {
        log.debug("Detecting content type for filename: {}", filename);
        
        if (filename == null || filename.isEmpty()) {
            log.debug("Filename is null or empty, returning application/octet-stream");
            return "application/octet-stream";
        }
        
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            log.debug("No valid extension found, returning application/octet-stream");
            return "application/octet-stream";
        }
        
        String extension = filename.substring(lastDotIndex).toLowerCase();
        log.debug("Found extension: {}", extension);
        
        switch (extension) {
            case ".pdf":
                log.debug("Returning application/pdf");
                return "application/pdf";
            case ".txt":
                log.debug("Returning text/plain");
                return "text/plain";
            default:
                log.debug("Returning application/octet-stream for extension: {}", extension);
                return "application/octet-stream";
        }
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
    @Transactional
    public void deleteDocument(String username, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        
        if (!document.getUploadedBy().equals(user)) {
            throw new RuntimeException("User not authorized to delete this document");
        }
        
        // Delete file from disk
        try {
            Files.deleteIfExists(Paths.get(document.getFilePath()));
        } catch (IOException e) {
            log.warn("Failed to delete file from disk: {}", document.getFilePath(), e);
        }
        
        documentRepository.delete(document);
    }

    @Override
    @Transactional
    public DocumentDto reprocessDocument(String username, UUID documentId, ProcessDocumentRequest request) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        
        if (!document.getUploadedBy().equals(user)) {
            throw new RuntimeException("User not authorized to reprocess this document");
        }
        
        // Read file content
        byte[] fileContent;
        try {
            fileContent = Files.readAllBytes(Paths.get(document.getFilePath()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read document file", e);
        }
        
        // Delete existing chunks
        chunkRepository.deleteByDocument(document);
        
        // Reprocess document
        try {
            processDocument(document, fileContent, request);
        } catch (Exception e) {
            document.setStatus(Document.DocumentStatus.FAILED);
            document.setProcessingError(e.getMessage());
            documentRepository.save(document);
            throw new RuntimeException("Failed to reprocess document: " + e.getMessage(), e);
        }
        
        return documentMapper.toDto(document);
    }

    @Override
    public DocumentDto getDocumentStatus(UUID documentId) {
        return getDocumentById(documentId);
    }
} 