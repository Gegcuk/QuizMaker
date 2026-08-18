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
import org.springframework.security.core.Authentication;
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
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

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

    private final NormalizedDocumentAccessService documentAccessService;
    private final DocumentMapper mapper;

    @Operation(
            summary = "Ingest text document",
            description = "Ingests plain text content for the authenticated owner, normalizes it, and stores it for quiz generation. The owner is resolved from authentication and cannot be supplied by the client."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Document ingested successfully",
                    content = @Content(schema = @Schema(implementation = IngestResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request - text is blank or validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Authenticated owner account not found",
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
            @Parameter(description = "Optional original filename") @RequestParam(value = "originalName", required = false) String originalName,
            Authentication authentication) throws IOException {
        
        String name = originalName != null ? originalName : "text-input";
        NormalizedDocument document = documentAccessService.ingestFromText(
                principalName(authentication), name, request.language(), request.text());
        
        URI location = URI.create("/api/v1/documentProcess/documents/" + document.getId());
        return ResponseEntity.created(location).body(mapper.toIngestResponse(document));
    }

    @Operation(
            summary = "Ingest file document",
            description = "Uploads a document for the authenticated owner, converts it to text, normalizes it, and stores it for quiz generation. The owner is resolved from authentication and cannot be supplied by the client."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Document ingested successfully",
                    content = @Content(schema = @Schema(implementation = IngestResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "File is empty or missing",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Authenticated owner account not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported file format",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Conversion or normalization failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> ingestFile(
            @Parameter(description = "Document file to upload", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "Optional original filename override") @RequestParam(value = "originalName", required = false) String originalName,
            Authentication authentication) throws IOException {
        
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        
        String name = originalName != null ? originalName :
                     (file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin");
        NormalizedDocument document = documentAccessService.ingestFromFile(
                principalName(authentication), name, file.getBytes());
        
        URI location = URI.create("/api/v1/documentProcess/documents/" + document.getId());
        return ResponseEntity.created(location).body(mapper.toIngestResponse(document));
    }

    @Operation(
            summary = "Get document metadata",
            description = "Retrieves metadata for the authenticated document owner. Missing, unowned, and other users' documents all return 404."
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
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public DocumentView getDocument(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            Authentication authentication) {

        NormalizedDocument document = documentAccessService.getDocument(principalName(authentication), id);
        return mapper.toDocumentView(document);
    }

    @Operation(
            summary = "Get document head (lightweight metadata)",
            description = "Retrieves lightweight metadata for the authenticated document owner. Missing, unowned, and other users' documents all return 404."
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
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}/head")
    public DocumentView getDocumentHead(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            Authentication authentication) {

        NormalizedDocument document = documentAccessService.getDocument(principalName(authentication), id);
        return mapper.toDocumentView(document);
    }

    @Operation(
            summary = "Get text slice",
            description = "Retrieves a character-offset slice for the authenticated document owner. Missing, unowned, and other users' documents all return 404."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Text slice retrieved",
                    content = @Content(schema = @Schema(implementation = TextSliceResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid offsets (negative or end < start)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
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
            @Parameter(description = "End offset (exclusive, defaults to document length)") @RequestParam(value = "end", required = false) @Min(0) Integer end,
            Authentication authentication) {

        if (end == null) {
            // Use char count to compute default end without loading the whole text
            end = documentAccessService.getTextLength(principalName(authentication), id);
        }
        
        String sliceText = documentAccessService.getTextSlice(principalName(authentication), id, start, end);
        return mapper.toTextSliceResponse(id, start, end, sliceText);
    }

    @Operation(
            summary = "Get document structure",
            description = "Retrieves structure for the authenticated document owner. format=tree returns nested children; format=flat returns nodes in document order. Missing, unowned, and other users' documents all return 404."
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
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
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
            ) @RequestParam(value = "format", defaultValue = "tree") String format,
            Authentication authentication) {

        return switch (format.toLowerCase()) {
            case "tree" -> {
                StructureTreeResponse response = documentAccessService.getTree(principalName(authentication), id);
                yield ResponseEntity.ok(response);
            }
            case "flat" -> {
                StructureFlatResponse response = documentAccessService.getFlat(principalName(authentication), id);
                yield ResponseEntity.ok(response);
            }
            default -> {
                log.warn("Invalid normalized-document structure format requested");
                throw new IllegalArgumentException("Invalid format. Use 'tree' or 'flat'");
            }
        };
    }

    @Operation(
            summary = "Build document structure",
            description = "Triggers AI-based structure extraction for the authenticated document owner. Missing, unowned, and other users' documents all return 404."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Structure built successfully",
                    content = @Content(schema = @Schema(implementation = StructureBuildResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Structure building failed (see message)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected error during structure building",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{id}/structure")
    public ResponseEntity<StructureBuildResponse> buildStructure(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            Authentication authentication) {
        log.info("Structure building requested for document: {}", id);
        
        try {
            documentAccessService.buildStructure(principalName(authentication), id);
            
            StructureBuildResponse response = new StructureBuildResponse("STRUCTURED", "Structure built successfully");
            return ResponseEntity.ok(response);
            
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (IllegalStateException e) {
            log.warn("Structure building failed for document: {}", id);
            
            StructureBuildResponse response = new StructureBuildResponse("FAILED", "Structure could not be built");
            return ResponseEntity.badRequest().body(response);
            
        } catch (Exception e) {
            log.error("Unexpected error building structure for document: {}", id);
            
            StructureBuildResponse response = new StructureBuildResponse("ERROR", "An unexpected error occurred");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @Operation(
            summary = "Extract text by node",
            description = "Extracts text for a structural node for the authenticated document owner. Missing, unowned, and other users' documents all return 404."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Node content extracted",
                    content = @Content(schema = @Schema(implementation = ExtractResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Node does not belong to document",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Document or node not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}/extract")
    public ExtractResponse extractByNode(
            @Parameter(description = "Document UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Node UUID to extract", required = true) @RequestParam("nodeId") UUID nodeId,
            Authentication authentication) {

        return documentAccessService.extractByNode(principalName(authentication), id, nodeId);
    }

    private String principalName(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
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
