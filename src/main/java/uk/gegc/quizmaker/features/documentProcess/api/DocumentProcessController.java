package uk.gegc.quizmaker.features.documentProcess.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
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
@Tag(name = "Document Processing", description = "Document ingestion, normalization, and structure extraction")
@SecurityRequirement(name = "Bearer Authentication")
public class DocumentProcessController {

    private final DocumentIngestionService ingestionService;
    private final DocumentQueryService queryService;
    private final StructureService structureService;
    private final DocumentMapper mapper;

    @Operation(
            summary = "Ingest text document",
            description = "Ingests plain text content, normalizes it, and stores for quiz generation"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Document ingested successfully",
                    content = @Content(schema = @Schema(implementation = IngestResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request - text is blank or validation failed"),
            @ApiResponse(responseCode = "422", description = "Normalization failed")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestJson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Text content and language",
                    required = true
            )
            @Valid @RequestBody IngestRequest request,
            @Parameter(description = "Optional original filename") @RequestParam(value = "originalName", required = false) String originalName) throws IOException {
        
        log.info("Ingesting JSON document: originalName={}", originalName);
        
        String name = originalName != null ? originalName : "text-input";
        NormalizedDocument document = ingestionService.ingestFromText(name, request.language(), request.text());
        
        URI location = URI.create("/api/v1/documentProcess/documents/" + document.getId());
        return ResponseEntity.created(location).body(mapper.toIngestResponse(document));
    }

    @Operation(
            summary = "Ingest file document",
            description = "Uploads and ingests a document file (PDF, DOCX, TXT, etc.), converts to text, normalizes, and stores for quiz generation"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Document ingested successfully",
                    content = @Content(schema = @Schema(implementation = IngestResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "File is empty or missing"),
            @ApiResponse(responseCode = "415", description = "Unsupported file format"),
            @ApiResponse(responseCode = "422", description = "Conversion or normalization failed")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> ingestFile(
            @Parameter(description = "Document file to upload", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "Optional original filename override") @RequestParam(value = "originalName", required = false) String originalName) throws IOException {
        
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

    @Operation(
            summary = "Get document metadata",
            description = "Retrieves document metadata including status, character count, language, and timestamps"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Document metadata retrieved",
                    content = @Content(schema = @Schema(implementation = DocumentView.class))
            ),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/{id}")
    public DocumentView getDocument(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id) {

        NormalizedDocument document = queryService.getDocument(id);
        return mapper.toDocumentView(document);
    }

    @Operation(
            summary = "Get document head (lightweight metadata)",
            description = "Retrieves document metadata without loading the full normalized text"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Document metadata retrieved",
                    content = @Content(schema = @Schema(implementation = DocumentView.class))
            ),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/{id}/head")
    public DocumentView getDocumentHead(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id) {

        NormalizedDocument document = queryService.getDocument(id);
        return mapper.toDocumentView(document);
    }

    @Operation(
            summary = "Get text slice",
            description = "Retrieves a portion of the normalized text by character offsets (start inclusive, end exclusive)"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Text slice retrieved",
                    content = @Content(schema = @Schema(implementation = TextSliceResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid offsets (negative or end < start)"),
            @ApiResponse(responseCode = "404", description = "Document not found"),
            @ApiResponse(responseCode = "422", description = "Document has no normalized text")
    })
    @GetMapping("/{id}/text")
    public TextSliceResponse getTextSlice(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Start offset (inclusive)", example = "0") @RequestParam(value = "start", defaultValue = "0") @Min(0) int start,
            @Parameter(description = "End offset (exclusive, defaults to document length)") @RequestParam(value = "end", required = false) @Min(0) Integer end) {

        if (end == null) {
            // Use char count to compute default end without loading the whole text
            end = queryService.getTextLength(id);
        }
        
        String sliceText = queryService.getTextSlice(id, start, end);
        return mapper.toTextSliceResponse(id, start, end, sliceText);
    }

    @Operation(
            summary = "Get document structure",
            description = "Retrieves the hierarchical structure of the document (chapters, sections, etc.) in tree or flat format"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Structure retrieved (format depends on 'format' parameter)"
            ),
            @ApiResponse(responseCode = "400", description = "Invalid format parameter (use 'tree' or 'flat')"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/{id}/structure")
    public ResponseEntity<?> getStructure(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Format: 'tree' (hierarchical) or 'flat' (linear)", example = "tree") @RequestParam(value = "format", defaultValue = "tree") String format) {

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

    @Operation(
            summary = "Build document structure",
            description = "Triggers AI-based structure extraction to identify chapters, sections, and hierarchical organization"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Structure built successfully",
                    content = @Content(schema = @Schema(implementation = StructureBuildResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Structure building failed (see message)"),
            @ApiResponse(responseCode = "404", description = "Document not found"),
            @ApiResponse(responseCode = "500", description = "Unexpected error during structure building")
    })
    @PostMapping("/{id}/structure")
    public ResponseEntity<StructureBuildResponse> buildStructure(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id) {
        log.info("Structure building requested for document: {}", id);
        
        try {
            structureService.buildStructure(id);
            
            StructureBuildResponse response = new StructureBuildResponse("STRUCTURED", "Structure built successfully");
            return ResponseEntity.ok(response);
            
        } catch (IllegalStateException e) {
            log.warn("Structure building failed for document: {} - {}", id, e.getMessage());
            
            StructureBuildResponse response = new StructureBuildResponse("FAILED", e.getMessage());
            return ResponseEntity.badRequest().body(response);
            
        } catch (Exception e) {
            log.error("Unexpected error building structure for document: {}", id, e);
            
            StructureBuildResponse response = new StructureBuildResponse("ERROR", "An unexpected error occurred");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @Operation(
            summary = "Extract text by node",
            description = "Extracts text content for a specific structural node (chapter, section, etc.) using pre-calculated offsets"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Node content extracted",
                    content = @Content(schema = @Schema(implementation = ExtractResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Node does not belong to document"),
            @ApiResponse(responseCode = "404", description = "Document or node not found")
    })
    @GetMapping("/{id}/extract")
    public ExtractResponse extractByNode(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Node UUID to extract", required = true) @RequestParam("nodeId") UUID nodeId) {

        return structureService.extractByNode(id, nodeId);
    }

    /**
     * Response DTO for structure building operations.
     */
    @Schema(name = "StructureBuildResponse", description = "Result of structure building operation")
    public record StructureBuildResponse(
            @Schema(description = "Status: STRUCTURED, FAILED, or ERROR", example = "STRUCTURED") String status,
            @Schema(description = "Human-readable message", example = "Structure built successfully") String message
    ) {}
}
