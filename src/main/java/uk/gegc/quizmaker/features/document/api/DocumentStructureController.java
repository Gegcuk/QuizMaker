package uk.gegc.quizmaker.features.document.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import uk.gegc.quizmaker.features.document.api.dto.DocumentNodeDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentStructureJobDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentTreeDto;
import uk.gegc.quizmaker.features.document.application.DocumentStructureJobService;
import uk.gegc.quizmaker.features.document.application.DocumentStructureService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentNodeMapper;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentStructureJobMapper;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Document Structure", description = "Document structure management endpoints")
public class DocumentStructureController {

    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentNodeMapper documentNodeMapper;
    private final DocumentStructureJobService jobService;
    private final DocumentStructureJobMapper jobMapper;
    private final DocumentStructureService documentStructureService;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;

    @PostMapping("/{documentId}/structure")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Start structure extraction", description = "Starts asynchronous document structure extraction job")
    public ResponseEntity<DocumentStructureJobDto> startStructureExtraction(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            @Parameter(description = "Extraction strategy") @RequestParam(defaultValue = "AI") DocumentNode.Strategy strategy,
            Authentication authentication) {

        String username = authentication.getName();
        
        // Check rate limit (1 extraction per minute per user)
        rateLimitService.checkRateLimit("structure_extraction", username, 1);
        
        // Validate document exists and user has access
        documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
        
        // Get user entity
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        // Create the job
        DocumentStructureJob job = jobService.createJob(user, documentId, strategy);
        
        // Start async extraction
        documentStructureService.extractAndAlignStructureAsync(job.getId());
        
        // Build the job status URI for LRO pattern
        URI jobUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/jobs/{id}")
                .buildAndExpand(job.getId())
                .toUri();

        DocumentStructureJobDto jobDto = jobMapper.toDto(job);
        
        log.info("Started structure extraction job {} for document {} with strategy {} for user {}", 
                job.getId(), documentId, strategy, username);
        
        return ResponseEntity.accepted()
                .location(jobUri)
                .header("Operation-Location", jobUri.toString())
                .header(HttpHeaders.RETRY_AFTER, "5") // 5 seconds polling interval
                .body(jobDto);
    }

    @Transactional(readOnly = true)
    @GetMapping("/{documentId}/structure")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Get document structure", description = "Retrieves the document structure in tree or flat format")
    public ResponseEntity<?> getDocumentStructure(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            @Parameter(description = "Response format") @RequestParam(defaultValue = "tree") String format,
            Authentication authentication) {

        // First, get the document to ensure it exists and get its title
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));

        // Check if document has structure
        if (!documentStructureService.hasDocumentStructure(documentId)) {
            throw new ResourceNotFoundException("Document structure not found. Please extract structure first.");
        }

        // Handle different formats
        if ("flat".equalsIgnoreCase(format)) {
            List<DocumentNode> nodes = documentStructureService.getDocumentStructureFlat(documentId);
            List<DocumentNodeDto> flatDtos = documentNodeMapper.toFlatDtoList(nodes);
            return ResponseEntity.ok(flatDtos);
        } else if ("tree".equalsIgnoreCase(format)) {
            List<DocumentNode> rootNodes = documentStructureService.getDocumentStructureTree(documentId);
            DocumentTreeDto treeDto = documentNodeMapper.toTreeDto(rootNodes, document);
            return ResponseEntity.ok(treeDto);
        } else {
            throw new ValidationException("Invalid format parameter. Must be 'tree' or 'flat'");
        }
    }

    @GetMapping("/{documentId}/structure/flat")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Get document structure as flat list", description = "Retrieves all document nodes in a flat list ordered by start offset")
    @Deprecated(since = "Use GET /documents/{documentId}/structure?format=flat instead")
    public ResponseEntity<List<DocumentNodeDto>> getDocumentStructureFlat(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            Authentication authentication) {

        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }

        // Get all nodes for this document ordered by start offset (global ordering)
        List<DocumentNode> nodes = documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId);

        List<DocumentNodeDto> flatDtos = documentNodeMapper.toFlatDtoList(nodes);
        return ResponseEntity.ok(flatDtos);
    }

    @GetMapping("/{documentId}/structure/nodes/{nodeId}")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Get specific document node", description = "Retrieves a specific document node by ID")
    public ResponseEntity<DocumentNodeDto> getDocumentNode(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            @Parameter(description = "Node ID") @PathVariable UUID nodeId) {

        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }

        DocumentNode node = documentNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + nodeId));

        // Verify the node belongs to the specified document
        if (!node.getDocument().getId().equals(documentId)) {
            throw new ResourceNotFoundException("Document node not found in document: " + documentId);
        }

        DocumentNodeDto nodeDto = documentNodeMapper.toDto(node);
        return ResponseEntity.ok(nodeDto);
    }

    @GetMapping("/{documentId}/structure/nodes/{nodeId}/children")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Get node children", description = "Retrieves all children of a specific document node")
    public ResponseEntity<List<DocumentNodeDto>> getNodeChildren(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            @Parameter(description = "Node ID") @PathVariable UUID nodeId) {

        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }

        // Verify node exists and belongs to the document
        DocumentNode node = documentNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + nodeId));

        if (!node.getDocument().getId().equals(documentId)) {
            throw new ResourceNotFoundException("Document node not found in document: " + documentId);
        }

        // Get children of this node
        List<DocumentNode> children = documentNodeRepository.findByDocument_IdAndParent_IdOrderByOrdinalAsc(documentId, nodeId);

        List<DocumentNodeDto> childrenDtos = documentNodeMapper.toDtoList(children);
        return ResponseEntity.ok(childrenDtos);
    }

    @GetMapping("/{documentId}/structure/overlapping")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Find overlapping nodes", description = "Finds all nodes that overlap with the specified offset range")
    public ResponseEntity<List<DocumentNodeDto>> findOverlappingNodes(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            @Parameter(description = "Start offset") @RequestParam int startOffset,
            @Parameter(description = "End offset") @RequestParam int endOffset) {

        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }

        // Validate offset parameters
        if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
            throw new IllegalArgumentException("Invalid offset range: startOffset must be >= 0, endOffset must be > startOffset");
        }

        List<DocumentNode> overlappingNodes = documentNodeRepository.findOverlapping(documentId, startOffset, endOffset);

        List<DocumentNodeDto> nodeDtos = documentNodeMapper.toFlatDtoList(overlappingNodes);
        return ResponseEntity.ok(nodeDtos);
    }

    @GetMapping("/{documentId}/structure/type/{type}")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Get nodes by type", description = "Retrieves all nodes of a specific type for a document")
    public ResponseEntity<List<DocumentNodeDto>> getNodesByType(
            @Parameter(description = "Document ID") @PathVariable UUID documentId,
            @Parameter(description = "Node type") @PathVariable DocumentNode.NodeType type) {

        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }

        List<DocumentNode> nodes = documentNodeRepository.findByDocumentIdAndTypeOrderByStartOffset(documentId, type);

        List<DocumentNodeDto> nodeDtos = documentNodeMapper.toFlatDtoList(nodes);
        return ResponseEntity.ok(nodeDtos);
    }
}
