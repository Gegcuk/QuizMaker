package uk.gegc.quizmaker.features.document.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.document.api.dto.DocumentNodeDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentTreeDto;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentNodeMapper;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Document Structure", description = "Document structure management endpoints")
public class DocumentStructureController {

    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentNodeMapper documentNodeMapper;

    @Transactional(readOnly = true)
    @GetMapping("/{documentId}/structure")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Get document structure tree", description = "Retrieves the complete hierarchical structure of a document")
    public ResponseEntity<DocumentTreeDto> getDocumentTree(
            @Parameter(description = "Document ID") @PathVariable UUID documentId) {

        // First, get the document to ensure it exists and get its title
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));

        // Load all nodes once (ordered by startOffset) to avoid N+1 queries
        List<DocumentNode> allNodes = documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId);

        // Build tree in memory (O(n) approach)
        Map<UUID, DocumentNode> id2node = allNodes.stream()
                .collect(Collectors.toMap(DocumentNode::getId, Function.identity()));

        List<DocumentNode> rootNodes = new ArrayList<>();
        allNodes.forEach(node -> {
            DocumentNode parent = node.getParent();
            if (parent == null) {
                rootNodes.add(node);
            } else {
                DocumentNode parentNode = id2node.get(parent.getId());
                if (parentNode != null) {
                    parentNode.getChildren().add(node); // relies on @OrderBy for stable order
                }
            }
        });

        DocumentTreeDto treeDto = documentNodeMapper.toTreeDto(rootNodes, document);
        return ResponseEntity.ok(treeDto);
    }

    @GetMapping("/{documentId}/structure/flat")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Get document structure as flat list", description = "Retrieves all document nodes in a flat list ordered by start offset")
    public ResponseEntity<List<DocumentNodeDto>> getDocumentStructureFlat(
            @Parameter(description = "Document ID") @PathVariable UUID documentId) {

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
    @PreAuthorize("hasRole('USER')")
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
    @PreAuthorize("hasRole('USER')")
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
    @PreAuthorize("hasRole('USER')")
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
    @PreAuthorize("hasRole('USER')")
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
