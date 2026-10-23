package uk.gegc.quizmaker.features.documentProcess.application.impl;

import uk.gegc.quizmaker.features.documentProcess.application.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StructureServiceImpl implements StructureService {

    private final DocumentNodeRepository nodeRepository;
    private final NormalizedDocumentRepository documentRepository;
    private final DocumentNodeMapper nodeMapper;
    private final LlmClient llmClient;
    private final AnchorOffsetCalculator anchorOffsetCalculator;
    private final NodeHierarchyBuilder hierarchyBuilder;
    private final DocumentQueryService queryService;
    private final ChunkedStructureService chunkedStructureService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

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

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void buildStructure(UUID documentId) {
        buildStructure(documentId, LlmClient.StructureOptions.defaultOptions());
    }

    /** Trusted internal entry point; HTTP callers use the owner-scoped access service. */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void buildStructure(UUID documentId, LlmClient.StructureOptions options) {
        StructureBuildSource source = Objects.requireNonNull(
                transactionTemplate.execute(status -> readBuildSource(documentId)));
        List<DocumentNode> prepared = prepare(source, options, () -> { });
        transactionTemplate.executeWithoutResult(status -> publish(source, prepared));
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public StructureBuildSource readBuildSource(UUID documentId) {
        NormalizedDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        if (document.getNormalizedText() == null || document.getNormalizedText().isEmpty()) {
            throw new IllegalStateException("Document has no normalized text");
        }
        if (document.getStatus() != NormalizedDocument.DocumentStatus.NORMALIZED) {
            throw new IllegalStateException("Document must be in NORMALIZED status, but was: " + document.getStatus());
        }
        return new StructureBuildSource(documentId,
                document.getOwner() == null ? null : document.getOwner().getId(),
                document.getVersion(), document.getNormalizedText(), document.getCharCount());
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public List<DocumentNode> prepare(StructureBuildSource source, LlmClient.StructureOptions options,
                                      Runnable authorizeDispatch) {
        try {
            // 2. Generate structure using LLM (with chunking for large documents)
            List<DocumentNode> allNodes;

            String normalizedText = source.text();

            // Use configuration-based chunking decision
            // Get the configured character limit from DocumentChunkingConfig
            int maxSingleChunkChars = chunkedStructureService.getChunkingConfig().getMaxSingleChunkChars();

            // CRITICAL: Log the actual configuration value to debug
            log.warn("CRITICAL DEBUG: Document {} has {} characters", source.documentId(), normalizedText.length());
            log.warn("CRITICAL DEBUG: maxSingleChunkChars from config = {}", maxSingleChunkChars);
            log.warn("CRITICAL DEBUG: Will chunk if document length > maxSingleChunkChars: {} > {} = {}",
                normalizedText.length(), maxSingleChunkChars, normalizedText.length() > maxSingleChunkChars);

            // SAFETY: If config returns 0 or very high value, use a reasonable default
            // Use 3x the configured limit as reasonable maximum
            int reasonableMax = Math.max(150_000 * 3, chunkedStructureService.getChunkingConfig().getMaxSingleChunkChars() * 3);
            if (maxSingleChunkChars <= 0 || maxSingleChunkChars > reasonableMax) {
                log.error("CRITICAL: Invalid maxSingleChunkChars: {}, using reasonable default", maxSingleChunkChars);
                // Use the configured default value (150,000 from DocumentChunkingConfig)
                maxSingleChunkChars = 150_000;
            }

            boolean needsChunking = normalizedText.length() > maxSingleChunkChars;

            log.info("Document {} processing decision: {} characters, max chars: {}, needs chunking: {}",
                    source.documentId(), normalizedText.length(), maxSingleChunkChars, needsChunking);

            // CRITICAL: Log the final decision
            log.warn("CRITICAL FINAL DECISION: Document {} - needsChunking = {}", source.documentId(), needsChunking);

            // CRITICAL SAFETY CHECK: Force chunking for documents that would fail in OpenAiLlmClient
            // OpenAiLlmClient rejects documents over its configured limit
            int openAiMaxChars = chunkedStructureService.getChunkingConfig().getMaxSingleChunkChars();
            if (normalizedText.length() > openAiMaxChars) {
                log.warn("CRITICAL: Document {} has {} characters - FORCING CHUNKED PROCESSING to prevent OpenAiLlmClient rejection! (max: {})",
                    source.documentId(), normalizedText.length(), openAiMaxChars);
                needsChunking = true;
            }

                        // CRITICAL EMERGENCY FIX: Force chunking for any document over 1M characters (2.5x the previous 400k limit)
            // This ensures we never send large documents to OpenAiLlmClient
            if (normalizedText.length() > 1_000_000) {
                log.error("CRITICAL EMERGENCY: Document {} has {} characters - FORCING CHUNKING regardless of config!",
                    source.documentId(), normalizedText.length());
                needsChunking = true;
            }

            // CRITICAL: Log the final decision before the if statement
            log.error("CRITICAL FINAL DECISION BEFORE IF: Document {} - needsChunking = {}, document length = {}",
                source.documentId(), needsChunking, normalizedText.length());

            // ABSOLUTE FINAL DECISION: Any document over 1M MUST be chunked (2.5x the previous 400k limit)
            if (normalizedText.length() > 1_000_000) {
                log.error("CRITICAL ABSOLUTE FINAL: Document {} has {} characters - FORCING CHUNKED PROCESSING!",
                    source.documentId(), normalizedText.length());
                allNodes = chunkedStructureService.processLargeDocument(
                    normalizedText, options, source.documentId().toString(), authorizeDispatch);
            } else if (needsChunking) {
                log.error("CRITICAL: Document {} is large, using chunked processing for {} characters",
                    source.documentId(), normalizedText.length());
                allNodes = chunkedStructureService.processLargeDocument(
                    normalizedText, options, source.documentId().toString(), authorizeDispatch);
            } else {
                log.error("CRITICAL: Document {} with {} characters is being processed in single pass",
                    source.documentId(), normalizedText.length());
                allNodes = llmClient.generateStructure(normalizedText, options, authorizeDispatch);
            }

            // Validate AI response
            if (allNodes == null || allNodes.isEmpty()) {
                throw new ResourceNotFoundException("Document not found or no nodes generated by AI: " + source.documentId());
            }

            return prepareNodesByLevel(allNodes, source);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (LlmClient.LlmException e) {
            throw new IllegalStateException("Failed to generate document structure", e);
        } catch (AnchorOffsetCalculator.AnchorNotFoundException e) {
            throw new IllegalStateException("Failed to calculate document offsets", e);
        } catch (Exception e) {
            log.warn("Document structure preparation failed for document: {}", source.documentId());
            throw new IllegalStateException("Unexpected error during structure building", e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(StructureBuildSource source, List<DocumentNode> nodes) {
        // CAS takes the document write lock without loading LONGTEXT or mutable source relationships.
        if (documentRepository.publishStructureVersion(source.documentId(), source.version(), clock.instant()) != 1) {
            throw new IllegalStateException("Document changed during structure building");
        }
        NormalizedDocument reference = documentRepository.getReferenceById(source.documentId());
        for (DocumentNode node : nodes) {
            node.setDocument(reference);
        }
        // Parents precede their children, keeping generated IDs and FK ordering explicit.
        try {
            nodes.stream().collect(Collectors.groupingBy(DocumentNode::getDepth, TreeMap::new, Collectors.toList()))
                    .values().forEach(nodeRepository::saveAll);
            nodeRepository.flush();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Unexpected error during structure building", failure);
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

    /** Prepare every level in memory; a failed later level has no persisted partial success. */
    private List<DocumentNode> prepareNodesByLevel(List<DocumentNode> aiNodes, StructureBuildSource source) {
        Map<Short, List<DocumentNode>> nodesByDepth = aiNodes.stream()
                .collect(Collectors.groupingBy(DocumentNode::getDepth, TreeMap::new, Collectors.toList()));
        List<DocumentNode> prepared = new ArrayList<>();
        for (Map.Entry<Short, List<DocumentNode>> level : nodesByDepth.entrySet()) {
            List<DocumentNode> nodes = createFreshNodeCopies(level.getValue());
            nodes = anchorOffsetCalculator.calculateOffsets(nodes, source.text());
            assignParentRelationships(nodes, prepared, level.getKey());
            validateNodes(nodes, source.charCount());
            prepared.addAll(nodes);
        }
        // Preserve existing advisory global validation; this change does not select stricter content policy.
        try {
            hierarchyBuilder.validateParentChildContainment(prepared);
            anchorOffsetCalculator.validateSiblingNonOverlap(prepared);
        } catch (Exception failure) {
            log.warn("Document structure global validation reported an issue");
        }
        return prepared;
    }

    /**
     * Assign parent relationships for nodes at a specific depth level.
     */
    private void assignParentRelationships(List<DocumentNode> levelNodes, List<DocumentNode> potentialParents, Short depth) {
        if (depth == 0) {
            // Root level nodes have no parent
            for (int i = 0; i < levelNodes.size(); i++) {
                levelNodes.get(i).setParent(null);
                levelNodes.get(i).setIdx(i);
            }
            return;
        }

        // Earlier prepared levels supply parents without relationship queries.

        // Group potential parents by depth for efficient lookup
        Map<Short, List<DocumentNode>> parentsByDepth = new HashMap<>();
        for (DocumentNode parent : potentialParents) {
            parentsByDepth.computeIfAbsent(parent.getDepth(), k -> new ArrayList<>()).add(parent);
        }

        // Assign parent and index for each node in this level
        Map<DocumentNode, Integer> childCountByParent = new IdentityHashMap<>();

        for (DocumentNode node : levelNodes) {
            DocumentNode bestParent = findBestParent(node, parentsByDepth);
            node.setParent(bestParent);

            // Assign index within parent's children
            int childIndex = childCountByParent.merge(bestParent, 0, (oldVal, val) -> oldVal + 1);
            node.setIdx(childIndex);

            log.debug("Assigned parent relationship and sibling index {}", childIndex);
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
                throw new IllegalArgumentException("Node start offset cannot be negative");
            }
            if (charCount != null && node.getEndOffset() > charCount) {
                throw new IllegalArgumentException("Node end offset exceeds document length");
            }
            if (node.getStartOffset() >= node.getEndOffset()) {
                throw new IllegalArgumentException("Node start offset must be less than end offset");
            }

            // Validate anchors
            if (node.getStartAnchor() == null || node.getStartAnchor().trim().isEmpty()) {
                throw new IllegalArgumentException("Node start anchor is required");
            }
            if (node.getEndAnchor() == null || node.getEndAnchor().trim().isEmpty()) {
                throw new IllegalArgumentException("Node end anchor is required");
            }

            // Validate required fields
            if (node.getType() == null) {
                throw new IllegalArgumentException("Node type is required");
            }
            if (node.getTitle() == null || node.getTitle().trim().isEmpty()) {
                throw new IllegalArgumentException("Node title is required");
            }
            if (node.getDepth() == null || node.getDepth() < 0) {
                throw new IllegalArgumentException("Node depth must be non-negative");
            }
        }

        // Note: Sibling overlap validation is done separately after hierarchy is built

        log.debug("Node validation passed for {} nodes", nodes.size());
    }

}
