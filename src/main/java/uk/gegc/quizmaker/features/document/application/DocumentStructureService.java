package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing document structure operations.
 * <p>
 * This service orchestrates the process of extracting, aligning, and persisting
 * document structure nodes. It coordinates between the outline extraction,
 * alignment, and persistence layers.
 * <p>
 * Implementation of document structure management from the chunk processing
 * improvement plan.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStructureService {

    private final CanonicalTextService canonicalTextService;
    private final PreSegmentationService preSegmentationService;
    private final OutlineExtractorService outlineExtractorService;
    private final OutlineAlignmentService outlineAlignmentService;
    private final DocumentNodeRepository documentNodeRepository;

    /**
     * Save aligned document nodes to the database.
     * <p>
     * This method persists the aligned document nodes with their hard offsets
     * and enforces data integrity constraints.
     *
     * @param documentId the document ID
     * @param nodes the list of aligned document nodes
     * @param sourceVersionHash the source version hash for determinism
     * @param strategy the strategy used for extraction (AI, REGEX, HYBRID)
     * @return the number of nodes saved
     */
    @Transactional
    public int saveNodes(UUID documentId, List<DocumentNode> nodes, String sourceVersionHash, DocumentNode.Strategy strategy) {
        // Validate input
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (nodes == null) {
            throw new IllegalArgumentException("Nodes cannot be null");
        }
        if (sourceVersionHash == null || sourceVersionHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Source version hash cannot be null or empty");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy cannot be null");
        }

        log.info("Saving {} document nodes for document {} with strategy {}", 
                nodes.size(), documentId, strategy);

        if (nodes.isEmpty()) {
            log.warn("No nodes to save for document {}", documentId);
            return 0;
        }

        // Delete existing nodes for this document to ensure clean slate
        deleteExistingNodes(documentId);

        // Set strategy and source version hash for all nodes
        for (DocumentNode node : nodes) {
            node.setStrategy(strategy);
            node.setSourceVersionHash(sourceVersionHash);
        }

        // Save all nodes
        List<DocumentNode> savedNodes = documentNodeRepository.saveAll(nodes);
        
        log.info("Successfully saved {} document nodes for document {}", 
                savedNodes.size(), documentId);
        
        return savedNodes.size();
    }

    /**
     * Extract and align document structure from canonical text.
     * <p>
     * This method performs the complete pipeline:
     * 1. Load or build canonical text
     * 2. Generate pre-segmentation windows
     * 3. Extract outline using LLM
     * 4. Align anchors to hard offsets
     * 5. Save nodes to database
     *
     * @param documentId the document ID
     * @param strategy the extraction strategy (AI, REGEX, HYBRID)
     * @return the number of nodes created
     */
    @Transactional
    public int extractAndAlignStructure(UUID documentId, DocumentNode.Strategy strategy) {
        log.info("Extracting and aligning document structure for document {} with strategy {}", 
                documentId, strategy);

        try {
            // Step 1: Load or build canonical text
            CanonicalTextService.CanonicalizedText canonicalText = canonicalTextService.loadOrBuild(documentId);
            
            // Step 2: Generate pre-segmentation windows
            List<PreSegmentationService.PreSegmentationWindow> windows = 
                    preSegmentationService.generateWindows(canonicalText);
            
            // Step 3: Extract outline using LLM
            DocumentOutlineDto outline = outlineExtractorService.extractOutline(canonicalText.getText());
            
            // Step 4: Align anchors to hard offsets
            List<DocumentNode> alignedNodes = outlineAlignmentService.alignOutlineToOffsets(
                    outline, canonicalText, windows, documentId, canonicalText.getSourceVersionHash());
            
            // Step 5: Save nodes to database
            return saveNodes(documentId, alignedNodes, canonicalText.getSourceVersionHash(), strategy);

        } catch (Exception e) {
            log.error("Failed to extract and align document structure for document {}", documentId, e);
            throw new DocumentProcessingException("Failed to extract document structure: " + e.getMessage(), e);
        }
    }

    /**
     * Get document structure as a tree.
     *
     * @param documentId the document ID
     * @return list of root document nodes with their children
     */
    @Transactional(readOnly = true)
    public List<DocumentNode> getDocumentStructureTree(UUID documentId) {
        log.debug("Retrieving document structure tree for document {}", documentId);
        
        List<DocumentNode> rootNodes = documentNodeRepository.findByDocument_IdAndParentIsNullOrderByOrdinalAsc(documentId);
        
        // Load children recursively
        for (DocumentNode rootNode : rootNodes) {
            loadChildrenRecursively(rootNode);
        }
        
        return rootNodes;
    }

    /**
     * Get document structure as a flat list.
     *
     * @param documentId the document ID
     * @return list of all document nodes ordered by start offset
     */
    @Transactional(readOnly = true)
    public List<DocumentNode> getDocumentStructureFlat(UUID documentId) {
        log.debug("Retrieving document structure flat list for document {}", documentId);
        return documentNodeRepository.findByDocumentIdOrderByStartOffset(documentId);
    }

    /**
     * Check if document has structure nodes.
     *
     * @param documentId the document ID
     * @return true if document has structure nodes, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean hasDocumentStructure(UUID documentId) {
        return documentNodeRepository.existsByDocument_Id(documentId);
    }

    /**
     * Get the count of structure nodes for a document.
     *
     * @param documentId the document ID
     * @return the number of structure nodes
     */
    @Transactional(readOnly = true)
    public long getDocumentStructureCount(UUID documentId) {
        return documentNodeRepository.countByDocument_Id(documentId);
    }

    /**
     * Delete all structure nodes for a document.
     *
     * @param documentId the document ID
     */
    @Transactional
    public void deleteDocumentStructure(UUID documentId) {
        log.info("Deleting document structure for document {}", documentId);
        documentNodeRepository.deleteByDocument_Id(documentId);
    }

    /**
     * Find overlapping nodes for a given range.
     *
     * @param documentId the document ID
     * @param startOffset the start offset
     * @param endOffset the end offset
     * @return list of overlapping nodes
     */
    @Transactional(readOnly = true)
    public List<DocumentNode> findOverlappingNodes(UUID documentId, int startOffset, int endOffset) {
        return documentNodeRepository.findOverlapping(documentId, startOffset, endOffset);
    }

    /**
     * Find nodes by type for a document.
     *
     * @param documentId the document ID
     * @param type the node type
     * @return list of nodes of the specified type
     */
    @Transactional(readOnly = true)
    public List<DocumentNode> findNodesByType(UUID documentId, DocumentNode.NodeType type) {
        return documentNodeRepository.findByDocumentIdAndTypeOrderByStartOffset(documentId, type);
    }

    /**
     * Load children recursively for a node.
     */
    private void loadChildrenRecursively(DocumentNode node) {
        List<DocumentNode> children = documentNodeRepository.findByDocument_IdAndParent_IdOrderByOrdinalAsc(
                node.getDocument().getId(), node.getId());
        
        for (DocumentNode child : children) {
            node.addChild(child);
            loadChildrenRecursively(child);
        }
    }

    /**
     * Delete existing nodes for a document.
     */
    private void deleteExistingNodes(UUID documentId) {
        long existingCount = documentNodeRepository.countByDocument_Id(documentId);
        if (existingCount > 0) {
            log.debug("Deleting {} existing nodes for document {}", existingCount, documentId);
            documentNodeRepository.deleteByDocument_Id(documentId);
        }
    }
}
