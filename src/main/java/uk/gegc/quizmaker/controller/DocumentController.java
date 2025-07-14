package uk.gegc.quizmaker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.config.DocumentProcessingConfig;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.DocumentProcessingService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentProcessingService documentProcessingService;
    private final DocumentProcessingConfig documentConfig;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDto> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkingStrategy", required = false) String chunkingStrategy,
            @RequestParam(value = "maxChunkSize", required = false) Integer maxChunkSize,
            Authentication authentication) {
        
        try {
            String username = authentication.getName();
            
            // Use configuration defaults if parameters are not provided
            ProcessDocumentRequest request = documentConfig.createDefaultRequest();
            
            if (chunkingStrategy != null) {
                request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.valueOf(chunkingStrategy.toUpperCase()));
            }
            if (maxChunkSize != null) {
                request.setMaxChunkSize(maxChunkSize);
            }
            
            DocumentDto document = documentProcessingService.uploadAndProcessDocument(
                    username, file.getBytes(), file.getOriginalFilename(), request);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(document);
        } catch (Exception e) {
            log.error("Error uploading document", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentDto> getDocument(@PathVariable UUID documentId) {
        try {
            DocumentDto document = documentProcessingService.getDocumentById(documentId);
            return ResponseEntity.ok(document);
        } catch (Exception e) {
            log.error("Error getting document: {}", documentId, e);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public ResponseEntity<Page<DocumentDto>> getUserDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        try {
            String username = authentication.getName();
            Pageable pageable = Pageable.ofSize(size).withPage(page);
            Page<DocumentDto> documents = documentProcessingService.getUserDocuments(username, pageable);
            return ResponseEntity.ok(documents);
        } catch (Exception e) {
            log.error("Error getting user documents", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{documentId}/chunks")
    public ResponseEntity<List<DocumentChunkDto>> getDocumentChunks(@PathVariable UUID documentId) {
        try {
            List<DocumentChunkDto> chunks = documentProcessingService.getDocumentChunks(documentId);
            return ResponseEntity.ok(chunks);
        } catch (Exception e) {
            log.error("Error getting document chunks: {}", documentId, e);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{documentId}/chunks/{chunkIndex}")
    public ResponseEntity<DocumentChunkDto> getDocumentChunk(
            @PathVariable UUID documentId, 
            @PathVariable Integer chunkIndex) {
        try {
            DocumentChunkDto chunk = documentProcessingService.getDocumentChunk(documentId, chunkIndex);
            return ResponseEntity.ok(chunk);
        } catch (Exception e) {
            log.error("Error getting document chunk: {}:{}", documentId, chunkIndex, e);
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID documentId, 
            Authentication authentication) {
        try {
            String username = authentication.getName();
            documentProcessingService.deleteDocument(username, documentId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting document: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/{documentId}/reprocess")
    public ResponseEntity<DocumentDto> reprocessDocument(
            @PathVariable UUID documentId,
            @RequestBody ProcessDocumentRequest request,
            Authentication authentication) {
        try {
            String username = authentication.getName();
            DocumentDto document = documentProcessingService.reprocessDocument(username, documentId, request);
            return ResponseEntity.ok(document);
        } catch (Exception e) {
            log.error("Error reprocessing document: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{documentId}/status")
    public ResponseEntity<DocumentDto> getDocumentStatus(@PathVariable UUID documentId) {
        try {
            DocumentDto document = documentProcessingService.getDocumentStatus(documentId);
            return ResponseEntity.ok(document);
        } catch (Exception e) {
            log.error("Error getting document status: {}", documentId, e);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/config")
    public ResponseEntity<DocumentProcessingConfig> getConfiguration() {
        return ResponseEntity.ok(documentConfig);
    }
} 