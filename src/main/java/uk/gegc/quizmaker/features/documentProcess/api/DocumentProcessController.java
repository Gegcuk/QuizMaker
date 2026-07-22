package uk.gegc.quizmaker.features.documentProcess.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import org.springframework.http.ProblemDetail;
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

    private static final String DOCUMENT_METADATA_EXAMPLE = """
            {
              "id": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
              "originalName": "learning-notes.txt",
              "mime": "text/plain",
              "source": "TEXT",
              "charCount": 15320,
              "language": "en",
              "status": "STRUCTURED",
              "createdAt": "2026-07-20T10:15:30Z",
              "updatedAt": "2026-07-20T10:16:02Z"
            }
            """;

    private static final String TREE_STRUCTURE_EXAMPLE = """
            {
              "documentId": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
              "rootNodes": [{
                "id": "f4bd0a47-4905-4d7e-aecb-e4f7e70b6a76",
                "documentId": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
                "parentId": null,
                "idx": 0,
                "type": "CHAPTER",
                "title": "Chapter 1: Introduction",
                "startOffset": 0,
                "endOffset": 3200,
                "depth": 0,
                "aiConfidence": 0.97,
                "metaJson": null,
                "children": [{
                  "id": "1d4181e7-6968-4d34-bb99-8443be696dbe",
                  "documentId": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
                  "parentId": "f4bd0a47-4905-4d7e-aecb-e4f7e70b6a76",
                  "idx": 0,
                  "type": "SECTION",
                  "title": "What you will learn",
                  "startOffset": 0,
                  "endOffset": 800,
                  "depth": 1,
                  "aiConfidence": 0.94,
                  "metaJson": null,
                  "children": []
                }]
              }],
              "totalNodes": 2
            }
            """;

    private static final String FLAT_STRUCTURE_EXAMPLE = """
            {
              "documentId": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
              "nodes": [{
                "id": "f4bd0a47-4905-4d7e-aecb-e4f7e70b6a76",
                "documentId": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
                "parentId": null,
                "idx": 0,
                "type": "CHAPTER",
                "title": "Chapter 1: Introduction",
                "startOffset": 0,
                "endOffset": 3200,
                "depth": 0,
                "aiConfidence": 0.97,
                "metaJson": null
              }, {
                "id": "1d4181e7-6968-4d34-bb99-8443be696dbe",
                "documentId": "14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a",
                "parentId": "f4bd0a47-4905-4d7e-aecb-e4f7e70b6a76",
                "idx": 0,
                "type": "SECTION",
                "title": "What you will learn",
                "startOffset": 0,
                "endOffset": 800,
                "depth": 1,
                "aiConfidence": 0.94,
                "metaJson": null
              }],
              "totalNodes": 2
            }
            """;

    private static final String INVALID_STRUCTURE_FORMAT_PROBLEM_EXAMPLE = """
            {
              "type": "https://quizzence.com/docs/errors/invalid-argument",
              "title": "Invalid Argument",
              "status": 400,
              "detail": "Invalid format. Use 'tree' or 'flat'",
              "instance": "/api/v1/documentProcess/documents/14f3c7e4-7a74-47d1-88e6-caa9adbb2b8a/structure"
            }
            """;

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
            @ApiResponse(responseCode = "400", description = "Invalid request - text is blank or validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Normalization failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
            @ApiResponse(responseCode = "400", description = "File is empty or missing",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported file format",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Conversion or normalization failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DocumentView.class),
                            examples = @ExampleObject(name = "Structured text document", value = DOCUMENT_METADATA_EXAMPLE)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DocumentView.class),
                            examples = @ExampleObject(name = "Structured text document", value = DOCUMENT_METADATA_EXAMPLE)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
            @ApiResponse(responseCode = "400", description = "Invalid offsets (negative or end < start)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Document has no normalized text",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
            description = "Retrieves the document structure. format=tree returns StructureTreeResponse with recursively nested children; format=flat returns StructureFlatResponse in document order."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Structure retrieved. The response is StructureTreeResponse for format=tree and StructureFlatResponse for format=flat.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(oneOf = {StructureTreeResponse.class, StructureFlatResponse.class}),
                            examples = {
                                    @ExampleObject(name = "Tree structure (format=tree)", value = TREE_STRUCTURE_EXAMPLE),
                                    @ExampleObject(name = "Flat structure (format=flat)", value = FLAT_STRUCTURE_EXAMPLE)
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid format parameter (use 'tree' or 'flat')",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(
                                    name = "Invalid structure format",
                                    value = INVALID_STRUCTURE_FORMAT_PROBLEM_EXAMPLE
                            )
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}/structure")
    public ResponseEntity<?> getStructure(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            @Parameter(
                    description = "Response format: tree returns nested children; flat returns nodes in document order",
                    schema = @Schema(allowableValues = {"tree", "flat"}, defaultValue = "tree"),
                    example = "tree"
            ) @RequestParam(value = "format", defaultValue = "tree") String format) {

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
                throw new IllegalArgumentException("Invalid format. Use 'tree' or 'flat'");
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
            @ApiResponse(responseCode = "400", description = "Structure building failed (see message)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected error during structure building",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
            @ApiResponse(responseCode = "400", description = "Node does not belong to document",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document or node not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
