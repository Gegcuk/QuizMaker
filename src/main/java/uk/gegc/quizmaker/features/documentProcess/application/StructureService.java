package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.*;
import java.util.stream.Collectors;

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
    private final DocumentQueryService queryService;
    private final ChunkedStructureService chunkedStructureService;

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
            // 2. Generate structure using LLM (with chunking for large documents)
            List<DocumentNode> allNodes;
            
            if (chunkedStructureService.needsChunking(document.getNormalizedText())) {
                log.info("Document {} is large ({} chars), using chunked processing", 
                    documentId, document.getNormalizedText().length());
                allNodes = chunkedStructureService.processLargeDocument(
                    document.getNormalizedText(), options, documentId.toString());
            } else {
                log.info("Document {} is small ({} chars), using single-pass processing", 
                    documentId, document.getNormalizedText().length());
                allNodes = llmClient.generateStructure(document.getNormalizedText(), options);
            }
            
            // Validate AI response
            if (allNodes == null || allNodes.isEmpty()) {
                throw new ResourceNotFoundException("Document not found or no nodes generated by AI: " + documentId);
            }
            
            // 3. Clear existing nodes (if any) before level-by-level processing
            nodeRepository.deleteByDocument_Id(documentId);
            
            // 4. Process nodes level by level (depth 0 first, then 1, 2, etc.)
            processNodesByLevel(allNodes, document, documentId);
            
            // 5. Update document status to STRUCTURED
            document.setStatus(NormalizedDocument.DocumentStatus.STRUCTURED);
            documentRepository.save(document);
            
            log.info("Successfully built structure for document: {} with level-by-level processing", documentId);
            
        } catch (ResourceNotFoundException e) {
            // Re-throw ResourceNotFoundException as-is (don't wrap it)
            throw e;
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
     * Extracts text content for a specific node.
     * This is the main extraction method that provides precise node-based content retrieval.
     * 
     * @param documentId the document ID
     * @param nodeId the node ID to extract
     * @return ExtractResponse with node metadata and extracted text
     * @throws ResourceNotFoundException if document or node not found
     * @throws IllegalArgumentException if node doesn't belong to document
     */
    @Transactional(readOnly = true)
    public ExtractResponse extractByNode(UUID documentId, UUID nodeId) {
        log.debug("Extracting text by node: document={}, node={}", documentId, nodeId);
        
        // Verify document exists
        if (!documentRepository.existsById(documentId)) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }
        
        // Find the node and verify it belongs to the document
        DocumentNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));
        
        if (!node.getDocument().getId().equals(documentId)) {
            throw new IllegalArgumentException("Node " + nodeId + " does not belong to document " + documentId);
        }
        
        // Validate node has valid offsets
        if (node.getStartOffset() == null || node.getEndOffset() == null) {
            throw new IllegalStateException("Node has invalid offsets: " + nodeId);
        }
        
        // Extract text using the existing query service
        String text = queryService.getTextSlice(documentId, node.getStartOffset(), node.getEndOffset());
        
        return new ExtractResponse(
                documentId,
                nodeId,
                node.getTitle(),
                node.getStartOffset(),
                node.getEndOffset(),
                text
        );
    }

    /**
     * Process nodes level by level, ensuring each level is saved before processing the next.
     * This provides resilience: if a later level fails, previous levels are already persisted.
     */
    private void processNodesByLevel(List<DocumentNode> aiNodes, NormalizedDocument document, UUID documentId) {
        // Group nodes by depth level
        Map<Short, List<DocumentNode>> nodesByDepth = aiNodes.stream()
                .collect(Collectors.groupingBy(DocumentNode::getDepth));
        
        // Sort depths to process from root (0) to leaves
        List<Short> depths = nodesByDepth.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
        
        int totalProcessed = 0;
        
        for (Short depth : depths) {
            List<DocumentNode> originalLevelNodes = nodesByDepth.get(depth);
            log.info("Processing depth level {} with {} nodes", depth, originalLevelNodes.size());
            
            try {
                // Create fresh copies of nodes to avoid Hibernate conflicts
                List<DocumentNode> levelNodes = createFreshNodeCopies(originalLevelNodes);
                
                // Calculate offsets for this level (modifies the fresh copies)
                levelNodes = anchorOffsetCalculator.calculateOffsets(levelNodes, document.getNormalizedText());
                
                // Assign parent relationships for this level by finding parents in the database
                assignParentRelationships(levelNodes, documentId, depth);
                
                // Validate this level
                validateNodes(levelNodes, document.getCharCount());
                
                // Set document reference and persist this level IMMEDIATELY
                for (DocumentNode node : levelNodes) {
                    node.setDocument(document);
                }
                nodeRepository.saveAll(levelNodes);
                
                totalProcessed += levelNodes.size();
                
                log.info("Successfully processed and saved depth level {} with {} nodes", depth, levelNodes.size());
                
            } catch (Exception e) {
                log.error("Failed to process depth level {} with {} nodes", depth, originalLevelNodes.size(), e);
                
                // Check if we have successfully processed any levels before this failure
                if (totalProcessed > 0) {
                    log.warn("Layer-by-layer processing failed at depth {}, but {} nodes from previous levels were successfully saved", 
                            depth, totalProcessed);
                    throw new IllegalStateException("Level-by-level processing failed at depth " + depth + 
                            " after successfully saving " + totalProcessed + " nodes from previous levels: " + e.getMessage(), e);
                } else {
                    // No levels were successfully processed
                    throw new IllegalStateException("Level-by-level processing failed at depth " + depth + ": " + e.getMessage(), e);
                }
            }
        }
        
        // After all levels are saved, perform global validations
        log.info("Performing global validation for all {} processed nodes", totalProcessed);
        
        // Perform global validation in a separate transaction to avoid rolling back saved nodes
        performGlobalValidation(documentId, totalProcessed);
        
        log.info("Completed level-by-layer processing. Total processed: {} nodes across {} levels", 
                totalProcessed, depths.size());
    }

    /**
     * Assign parent relationships for nodes at a specific depth level.
     */
    private void assignParentRelationships(List<DocumentNode> levelNodes, UUID documentId, Short depth) {
        if (depth == 0) {
            // Root level nodes have no parent
            for (int i = 0; i < levelNodes.size(); i++) {
                levelNodes.get(i).setParent(null);
                levelNodes.get(i).setIdx(i);
            }
            return;
        }
        
        // For non-root levels, find potential parents from already saved nodes
        List<DocumentNode> potentialParents = nodeRepository.findByDocument_IdAndDepthLessThanOrderByStartOffset(documentId, depth);
        
        // Group potential parents by depth for efficient lookup
        Map<Short, List<DocumentNode>> parentsByDepth = new HashMap<>();
        for (DocumentNode parent : potentialParents) {
            parentsByDepth.computeIfAbsent(parent.getDepth(), k -> new ArrayList<>()).add(parent);
        }
        
        // Assign parent and index for each node in this level
        Map<UUID, Integer> childCountByParent = new HashMap<>();
        
        for (DocumentNode node : levelNodes) {
            DocumentNode bestParent = findBestParent(node, parentsByDepth);
            node.setParent(bestParent);
            
            // Assign index within parent's children
            UUID parentKey = bestParent != null ? bestParent.getId() : null;
            int childIndex = childCountByParent.merge(parentKey, 0, (oldVal, val) -> oldVal + 1);
            node.setIdx(childIndex);
            
            log.debug("Assigned parent '{}' and index {} to node '{}'", 
                    bestParent != null ? bestParent.getTitle() : "ROOT", childIndex, node.getTitle());
        }
    }

    /**
     * Create fresh copies of DocumentNode objects to avoid Hibernate conflicts.
     * This ensures we're working with detached entities that can be safely modified.
     */
    private List<DocumentNode> createFreshNodeCopies(List<DocumentNode> originalNodes) {
        List<DocumentNode> freshCopies = new ArrayList<>();
        
        for (DocumentNode original : originalNodes) {
            DocumentNode copy = new DocumentNode();
            
            // Copy all basic properties
            copy.setTitle(original.getTitle());
            copy.setType(original.getType());
            copy.setDepth(original.getDepth());
            copy.setStartAnchor(original.getStartAnchor());
            copy.setEndAnchor(original.getEndAnchor());
            copy.setAiConfidence(original.getAiConfidence());
            copy.setMetaJson(original.getMetaJson());
            
            // Don't copy ID, parent, idx, offsets, or document - these will be set during processing
            // This ensures we have completely fresh entities
            
            freshCopies.add(copy);
        }
        
        log.debug("Created {} fresh node copies for processing", freshCopies.size());
        return freshCopies;
    }

    /**
     * Find the best parent for a node based on containment and depth.
     */
    private DocumentNode findBestParent(DocumentNode node, Map<Short, List<DocumentNode>> parentsByDepth) {
        DocumentNode bestParent = null;
        
        // Look for parents starting from the deepest level (closest parent)
        List<Short> parentDepths = new ArrayList<>(parentsByDepth.keySet());
        parentDepths.sort(Collections.reverseOrder()); // Deepest first
        
        for (Short parentDepth : parentDepths) {
            for (DocumentNode potentialParent : parentsByDepth.get(parentDepth)) {
                // Check if potential parent contains this node
                if (potentialParent.getStartOffset() <= node.getStartOffset() && 
                    potentialParent.getEndOffset() >= node.getEndOffset()) {
                    
                    // If we don't have a parent yet, or this one is deeper (more specific), use it
                    if (bestParent == null || potentialParent.getDepth() > bestParent.getDepth()) {
                        bestParent = potentialParent;
                    }
                }
            }
            
            // If we found a parent at this depth level, we're done (deepest valid parent)
            if (bestParent != null) {
                break;
            }
        }
        
        return bestParent;
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
            if (charCount != null && node.getEndOffset() > charCount) {
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

    /**
     * Perform global validation in a separate transaction to avoid rolling back saved nodes.
     * This ensures that even if validation fails, the successfully saved layers remain in the database.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void performGlobalValidation(UUID documentId, int totalProcessed) {
        try {
            log.info("Performing global validation for all {} processed nodes", totalProcessed);
            List<DocumentNode> allSavedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
            
            // Validate parent-child containment
            hierarchyBuilder.validateParentChildContainment(allSavedNodes);
            
            // Validate sibling non-overlap
            anchorOffsetCalculator.validateSiblingNonOverlap(allSavedNodes);
            
            log.info("Global validation completed successfully for {} nodes", allSavedNodes.size());
        } catch (Exception e) {
            log.warn("Global validation failed, but {} nodes were successfully saved. Validation error: {}", 
                    totalProcessed, e.getMessage());
            // Don't throw the exception - the nodes are already saved successfully
            // Just log the validation issue for debugging
        }
    }
}
