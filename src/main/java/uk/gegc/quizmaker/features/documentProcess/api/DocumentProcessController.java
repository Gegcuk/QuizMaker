package uk.gegc.quizmaker.features.documentProcess.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.documentProcess.api.dto.DocumentView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestRequest;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.TextSliceResponse;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * REST controller for document processing operations.
 * Handles document ingestion and text retrieval.
 */
@RestController
@RequestMapping("/api/v1/documentProcess/documents")
@RequiredArgsConstructor
@Validated
@Slf4j
public class DocumentProcessController {

    private final DocumentIngestionService ingestionService;
    private final DocumentQueryService queryService;
    private final StructureService structureService;
    private final DocumentMapper mapper;

    /**
     * Ingests a document from JSON text.
     * 
     * @param request JSON request with text content
     * @param originalName original filename (optional)
     * @return ingestion response with document ID and status
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestJson(
            @Valid @RequestBody IngestRequest request,
            @RequestParam(value = "originalName", required = false) String originalName) throws IOException {
        
        log.info("Ingesting JSON document: originalName={}", originalName);
        
        String name = originalName != null ? originalName : "text-input";
        NormalizedDocument document = ingestionService.ingestFromText(name, request.language(), request.text());
        
        URI location = URI.create("/api/v1/documentProcess/documents/" + document.getId());
        return ResponseEntity.created(location).body(mapper.toIngestResponse(document));
    }

    /**
     * Ingests a document from multipart file upload.
     * 
     * @param file multipart file upload
     * @param originalName original filename (optional)
     * @return ingestion response with document ID and status
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "originalName", required = false) String originalName) throws IOException {
        
        log.info("Ingesting file document: originalName={}", originalName);
        
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        
        String name = originalName != null ? originalName :
                     (file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin");
        NormalizedDocument document = ingestionService.ingestFromFile(name, file.getBytes());
        
        URI location = URI.create("/api/v1/documentProcess/documents/" + document.getId());
        return ResponseEntity.created(location).body(mapper.toIngestResponse(document));
    }

    /**
     * Retrieves document metadata by ID.
     * 
     * @param id the document ID
     * @return document metadata
     */
    @GetMapping("/{id}")
    public DocumentView getDocument(@PathVariable UUID id) {
        log.debug("Retrieving document metadata: {}", id);
        
        NormalizedDocument document = queryService.getDocument(id);
        return mapper.toDocumentView(document);
    }

    /**
     * Retrieves lightweight document info (id, charCount, status) without loading the full text.
     * 
     * @param id the document ID
     * @return lightweight document info
     */
    @GetMapping("/{id}/head")
    public DocumentView getDocumentHead(@PathVariable UUID id) {
        log.debug("Retrieving document head: {}", id);
        
        NormalizedDocument document = queryService.getDocument(id);
        return mapper.toDocumentView(document);
    }

    /**
     * Retrieves a text slice from a document.
     * 
     * @param id the document ID
     * @param start start offset (inclusive, default 0)
     * @param end end offset (exclusive, optional - defaults to end of text)
     * @return text slice response
     */
    @GetMapping("/{id}/text")
    public TextSliceResponse getTextSlice(
            @PathVariable UUID id,
            @RequestParam(value = "start", defaultValue = "0") @Min(0) int start,
            @RequestParam(value = "end", required = false) @Min(0) Integer end) {
        
        log.debug("Retrieving text slice: document={}, start={}, end={}", id, start, end);
        
        if (end == null) {
            // Use char count to compute default end without loading the whole text
            end = queryService.getTextLength(id);
        }
        
        String sliceText = queryService.getTextSlice(id, start, end);
        return mapper.toTextSliceResponse(id, start, end, sliceText);
    }

    /**
     * Retrieves the document structure in the specified format.
     * Phase 2: Returns empty structure as AI functionality is not yet implemented.
     * 
     * @param id the document ID
     * @param format structure format: "tree" (hierarchical) or "flat" (linear)
     * @return structure response based on format
     */
    @GetMapping("/{id}/structure")
    public ResponseEntity<?> getStructure(
            @PathVariable UUID id,
            @RequestParam(value = "format", defaultValue = "tree") String format) {
        
        log.debug("Retrieving document structure: document={}, format={}", id, format);
        
        return switch (format.toLowerCase()) {
            case "tree" -> {
                StructureTreeResponse response = structureService.getTree(id);
                yield ResponseEntity.ok(response);
            }
            case "flat" -> {
                StructureFlatResponse response = structureService.getFlat(id);
                yield ResponseEntity.ok(response);
            }
            default -> {
                log.warn("Invalid structure format requested: {}", format);
                yield ResponseEntity.badRequest()
                    .body("Invalid format. Use 'tree' or 'flat'");
            }
        };
    }

    /**
     * Placeholder endpoint for future structure building functionality.
     * Phase 2: Not implemented yet - will be added when AI functionality is implemented.
     * 
     * @param id the document ID
     * @return response indicating structure building is not yet available
     */
    @PostMapping("/{id}/structure")
    public ResponseEntity<String> buildStructure(@PathVariable UUID id) {
        log.info("Structure building requested for document: {}", id);
        
        return ResponseEntity.status(501)
            .body("Structure building will be available in Phase 3 (AI implementation)");
    }
}
