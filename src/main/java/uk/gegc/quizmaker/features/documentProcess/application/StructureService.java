package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Service for managing document structure operations.
 * Phase 2: Skeleton implementation returning empty structures (no AI yet).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StructureService {

    private final DocumentNodeRepository nodeRepository;
    private final NormalizedDocumentRepository documentRepository;
    private final DocumentNodeMapper nodeMapper;

    /**
     * Get the tree structure of a document.
     * Currently returns empty structure as AI functionality is not yet implemented.
     */
    @Transactional(readOnly = true)
    public StructureTreeResponse getTree(UUID documentId) {
        log.debug("Getting tree structure for document: {}", documentId);
        
        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }

        // Phase 2: Return empty structure (no AI yet)
        // In Phase 3, this will return actual AI-generated structure
        var allNodes = nodeRepository.findAllForTree(documentId);
        var rootViews = nodeMapper.buildTree(allNodes);
        var totalNodes = allNodes.size();
        
        return new StructureTreeResponse(
                documentId,
                rootViews,
                totalNodes
        );
    }

    /**
     * Get the flat structure of a document.
     * Currently returns empty structure as AI functionality is not yet implemented.
     */
    @Transactional(readOnly = true)
    public StructureFlatResponse getFlat(UUID documentId) {
        log.debug("Getting flat structure for document: {}", documentId);
        
        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }

        // Phase 2: Return empty structure (no AI yet)
        // In Phase 3, this will return actual AI-generated structure
        var nodes = nodeRepository.findAllByDocumentIdOrderByStartOffset(documentId);
        var totalNodes = nodeRepository.countByDocumentId(documentId);
        
        return new StructureFlatResponse(
                documentId,
                nodeMapper.toFlatNodeList(nodes),
                totalNodes
        );
    }

    /**
     * Placeholder for future AI structure building functionality.
     * Phase 2: Not implemented yet - will be added in Phase 3.
     */
    public void buildStructure(UUID documentId) {
        log.info("Structure building not yet implemented for document: {}", documentId);
        throw new UnsupportedOperationException("Structure building will be implemented in Phase 3");
    }

    /**
     * Validates that a node index is unique within its parent scope.
     * For future use when building structures.
     */
    private void validateUniqueIndex(UUID documentId, UUID parentId, Integer idx) {
        boolean exists = parentId == null 
            ? nodeRepository.existsByDocumentAndNullParentAndIdx(documentId, idx)
            : nodeRepository.existsByDocumentAndParentAndIdx(documentId, parentId, idx);
        
        if (exists) {
            throw new IllegalArgumentException(
                "Node with index %d already exists for document %s under parent %s".formatted(
                    idx, documentId, parentId != null ? parentId : "root"
                )
            );
        }
    }
}
