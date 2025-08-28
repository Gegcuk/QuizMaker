package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.List;
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
    private final LlmClient llmClient;
    private final AnchorOffsetCalculator anchorOffsetCalculator;
    private final NodeHierarchyBuilder hierarchyBuilder;

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
     * Builds the structure for a document using AI.
     * Phase 3: Single-pass AI structure generation.
     */
    @Transactional
    public void buildStructure(UUID documentId) {
        buildStructure(documentId, LlmClient.StructureOptions.defaultOptions());
    }

    /**
     * Builds the structure for a document using AI with custom options.
     * Phase 3: Single-pass AI structure generation.
     */
    @Transactional
    public void buildStructure(UUID documentId, LlmClient.StructureOptions options) {
        log.info("Building structure for document: {} with options: {}", documentId, options);
        
        // 1. Fetch normalized text
        NormalizedDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        
        if (document.getNormalizedText() == null || document.getNormalizedText().isEmpty()) {
            throw new IllegalStateException("Document has no normalized text: " + documentId);
        }
        
        if (document.getStatus() != NormalizedDocument.DocumentStatus.NORMALIZED) {
            throw new IllegalStateException("Document must be in NORMALIZED status, but was: " + document.getStatus());
        }
        
        try {
            // 2. Generate structure using LLM (with anchors instead of offsets)
            List<DocumentNode> nodes = llmClient.generateStructure(document.getNormalizedText(), options);
            
            // 3. Calculate offsets from anchors
            nodes = anchorOffsetCalculator.calculateOffsets(nodes, document.getNormalizedText());
            
            // 4. Build parent-child hierarchy and assign proper indices
            nodes = hierarchyBuilder.buildHierarchy(nodes);
            
            // 5. Validate parent-child containment
            hierarchyBuilder.validateParentChildContainment(nodes);
            
            // 6. Validate sibling non-overlap (after hierarchy is built)
            anchorOffsetCalculator.validateSiblingNonOverlap(nodes);
            
            // 7. Validate nodes
            validateNodes(nodes, document.getCharCount());
            
            // 8. Clear existing nodes (if any)
            nodeRepository.deleteByDocument_Id(documentId);
            
            // 9. Set document reference and persist nodes
            for (DocumentNode node : nodes) {
                node.setDocument(document);
            }
            
            nodeRepository.saveAll(nodes);
            
            // 10. Update document status to STRUCTURED
            document.setStatus(NormalizedDocument.DocumentStatus.STRUCTURED);
            documentRepository.save(document);
            
            log.info("Successfully built structure for document: {} with {} nodes", documentId, nodes.size());
            
        } catch (LlmClient.LlmException e) {
            log.error("AI structure generation failed for document: {}", documentId, e);
            throw new IllegalStateException("Failed to generate structure: " + e.getMessage(), e);
        } catch (AnchorOffsetCalculator.AnchorNotFoundException e) {
            log.error("Anchor not found during offset calculation for document: {}", documentId, e);
            throw new IllegalStateException("Failed to calculate offsets: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error building structure for document: {}", documentId, e);
            throw new IllegalStateException("Unexpected error during structure building", e);
        }
    }

    /**
     * Validates nodes for consistency and correctness.
     */
    private void validateNodes(List<DocumentNode> nodes, Integer charCount) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("No nodes generated");
        }
        
        for (DocumentNode node : nodes) {
            // Validate offsets (now calculated from anchors)
            if (node.getStartOffset() < 0) {
                throw new IllegalArgumentException("Node start offset cannot be negative: " + node.getTitle());
            }
            if (node.getEndOffset() > charCount) {
                throw new IllegalArgumentException("Node end offset exceeds document length: " + node.getTitle());
            }
            if (node.getStartOffset() >= node.getEndOffset()) {
                throw new IllegalArgumentException("Node start offset must be less than end offset: " + node.getTitle());
            }
            
            // Validate anchors
            if (node.getStartAnchor() == null || node.getStartAnchor().trim().isEmpty()) {
                throw new IllegalArgumentException("Node start anchor is required: " + node.getTitle());
            }
            if (node.getEndAnchor() == null || node.getEndAnchor().trim().isEmpty()) {
                throw new IllegalArgumentException("Node end anchor is required: " + node.getTitle());
            }
            
            // Validate required fields
            if (node.getType() == null) {
                throw new IllegalArgumentException("Node type is required: " + node.getTitle());
            }
            if (node.getTitle() == null || node.getTitle().trim().isEmpty()) {
                throw new IllegalArgumentException("Node title is required");
            }
            if (node.getDepth() == null || node.getDepth() < 0) {
                throw new IllegalArgumentException("Node depth must be non-negative: " + node.getTitle());
            }
        }
        
        // Note: Sibling overlap validation is done separately after hierarchy is built
        
        log.debug("Node validation passed for {} nodes", nodes.size());
    }
}
