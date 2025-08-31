package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for processing large documents by chunking them into manageable pieces,
 * processing each chunk with AI, and merging the results.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkedStructureService {

    private final DocumentChunker documentChunker;
    private final LlmClient llmClient;
    private final TokenCounter tokenCounter;
    private final DocumentChunkingConfig chunkingConfig;

    /**
     * Processes a large document by chunking it and processing chunks sequentially with context.
     * This ensures AI understands the continuity between chunks and avoids duplicate sections.
     * 
     * @param text the full document text
     * @param options structure generation options
     * @param documentId the document ID for logging
     * @return merged list of document nodes
     */
    public List<DocumentNode> processLargeDocument(String text, LlmClient.StructureOptions options, String documentId) {
        log.info("Processing large document {} with {} characters", documentId, text.length());
        
        // Step 1: Chunk the document
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunkDocument(text, documentId);
        
        if (chunks.size() == 1) {
            // CRITICAL SAFETY CHECK: If the single chunk is too large, force chunking
            if (text.length() > 1_000_000) { // 1M characters (2.5x the previous 400k limit)
                log.error("CRITICAL: Document {} has {} characters but was not chunked! Forcing emergency chunking!", 
                    documentId, text.length());
                // Force chunking by creating smaller chunks manually
                List<DocumentChunker.DocumentChunk> emergencyChunks = createEmergencyChunks(text, documentId);
                return processChunksSequentialWithContext(emergencyChunks, options, documentId);
            }
            
            // Small document, process normally but still apply filtering
            log.info("Document {} is small enough for single-chunk processing", documentId);
            List<DocumentNode> nodes = llmClient.generateStructure(text, options);
            return filterQuizRelevantNodes(nodes);
        }
        
        // Step 2: Process chunks sequentially with context
        List<DocumentNode> allNodes = processChunksSequentialWithContext(chunks, options, documentId);
        
        log.info("Successfully processed large document {}: {} chunks -> {} final nodes", 
            documentId, chunks.size(), allNodes.size());
        
        return allNodes;
    }
    

    
    /**
     * Processes document chunks sequentially with context from previous chunks.
     * This ensures AI understands the continuity and avoids generating duplicate sections.
     */
    private List<DocumentNode> processChunksSequentialWithContext(List<DocumentChunker.DocumentChunk> chunks, 
                                                                LlmClient.StructureOptions options, 
                                                                String documentId) {
        List<DocumentNode> allNodes = new ArrayList<>();
        List<DocumentNode> previousNodes = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            
            log.info("Processing chunk {} of {} for document {} ({} chars)", 
                i + 1, chunks.size(), documentId, chunk.getLength());
            
            try {
                // Create context-aware options with previous structure
                LlmClient.StructureOptions contextOptions = createContextOptions(options, previousNodes, i, chunks.size());
                
                // Generate structure for this chunk with context
                List<DocumentNode> chunkNodes = llmClient.generateStructureWithContext(
                    chunk.getText(), contextOptions, previousNodes, i, chunks.size());
                
                // Adjust offsets to global document coordinates
                adjustNodeOffsets(chunkNodes, chunk.getStartOffset());
                
                // Filter out unwanted sections (author, acknowledgments, etc.)
                List<DocumentNode> filteredNodes = filterQuizRelevantNodes(chunkNodes);
                
                // Add to our collection
                allNodes.addAll(filteredNodes);
                previousNodes.addAll(filteredNodes);
                
                log.info("Chunk {} completed with {} nodes ({} after filtering)", 
                    i + 1, chunkNodes.size(), filteredNodes.size());
                
            } catch (Exception e) {
                log.error("Failed to process chunk {} of document {}", i + 1, documentId, e);
                
                // If this is a "No nodes generated" error, try to create a fallback node
                if (e.getCause() instanceof LlmClient.LlmException && 
                    e.getCause().getMessage().contains("No nodes generated")) {
                    log.warn("Chunk {} returned no nodes - creating fallback node to continue processing", i + 1);
                    try {
                        // Create a fallback node for the entire chunk
                        List<DocumentNode> fallbackNodes = createFallbackNode(chunk, options, i, chunks.size());
                        adjustNodeOffsets(fallbackNodes, chunk.getStartOffset());
                        allNodes.addAll(fallbackNodes);
                        previousNodes.addAll(fallbackNodes);
                        log.info("Created fallback node for chunk {} with {} nodes", i + 1, fallbackNodes.size());
                        continue;
                    } catch (Exception fallbackError) {
                        log.error("Failed to create fallback node for chunk {}", i + 1, fallbackError);
                    }
                }
                
                throw new RuntimeException("Chunk processing failed", e);
            }
        }
        
        return allNodes;
    }
    
    /**
     * Creates context-aware options that include previously generated structure.
     */
    private LlmClient.StructureOptions createContextOptions(LlmClient.StructureOptions originalOptions, 
                                                          List<DocumentNode> previousNodes, 
                                                          int chunkIndex, 
                                                          int totalChunks) {
        // For now, we'll use the original options
        // TODO: Enhance LlmClient to support context from previous chunks
        return originalOptions;
    }
    
    /**
     * Adjusts node offsets from chunk-relative to document-relative coordinates.
     */
    private void adjustNodeOffsets(List<DocumentNode> nodes, int chunkStartOffset) {
        for (DocumentNode node : nodes) {
            if (node.getStartOffset() != null) {
                node.setStartOffset(node.getStartOffset() + chunkStartOffset);
            }
            if (node.getEndOffset() != null) {
                node.setEndOffset(node.getEndOffset() + chunkStartOffset);
            }
        }
    }
    
    /**
     * Creates emergency chunks for documents that were not properly chunked.
     * This is a safety mechanism to prevent large documents from being sent to the LLM.
     */
    private List<DocumentChunker.DocumentChunk> createEmergencyChunks(String text, String documentId) {
        log.error("CRITICAL: Creating emergency chunks for document {} with {} characters", documentId, text.length());
        
        List<DocumentChunker.DocumentChunk> emergencyChunks = new ArrayList<>();
        int chunkSize = 875_000; // 875k characters per chunk (2.5x the previous 350k limit)
        int overlap = 87_500; // 87.5k character overlap (10% of chunk size)
        
        for (int i = 0; i < text.length(); i += chunkSize - overlap) {
            int start = i;
            int end = Math.min(i + chunkSize, text.length());
            
            String chunkText = text.substring(start, end);
            DocumentChunker.DocumentChunk chunk = new DocumentChunker.DocumentChunk(
                chunkText, start, end, emergencyChunks.size()
            );
            emergencyChunks.add(chunk);
            
            log.error("CRITICAL: Created emergency chunk {}: positions {} to {} ({} chars)", 
                emergencyChunks.size(), start, end, chunkText.length());
            
            if (end >= text.length()) break;
        }
        
        log.error("CRITICAL: Created {} emergency chunks for document {}", emergencyChunks.size(), documentId);
        return emergencyChunks;
    }
    
    /**
     * Filters out nodes that are not relevant for quiz generation.
     * Removes sections like author info, acknowledgments, table of contents, etc.
     */
    private List<DocumentNode> filterQuizRelevantNodes(List<DocumentNode> nodes) {
        List<DocumentNode> relevantNodes = new ArrayList<>();
        
        for (DocumentNode node : nodes) {
            String title = node.getTitle().toLowerCase();
            
            // Skip non-quiz-relevant sections
            if (isQuizIrrelevant(title)) {
                log.debug("Filtering out quiz-irrelevant node: {}", node.getTitle());
                continue;
            }
            
            relevantNodes.add(node);
        }
        
        return relevantNodes;
    }
    
    /**
     * Determines if a node title indicates content that's not relevant for quiz generation.
     */
    private boolean isQuizIrrelevant(String title) {
        // Keywords that indicate non-quiz-relevant content
        String[] irrelevantKeywords = {
            "author", "authors", "acknowledgment", "acknowledgments", "thanks", "thank you",
            "table of contents", "contents", "index", "glossary", "bibliography",
            "appendix", "appendices", "preface", "foreword", "abstract",
            "dedication", "copyright", "license", "permission", "about the author",
            "about the authors", "biography", "biographies", "contact", "email", "website",
            "publisher", "publication", "edition", "isbn", "doi", "doi:", "isbn:",
            "chapter 0", "chapter zero", "preliminary", "front matter", "back matter"
        };
        
        for (String keyword : irrelevantKeywords) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Determines if a document needs chunking based on its token count.
     * Uses token estimation to determine if the text exceeds model limits.
     * 
     * CRITICAL: This method MUST return true for large documents to prevent
     * the system from giving up and throwing "document too large" errors.
     */
    public boolean needsChunking(String text) {
        if (text == null || text.isEmpty()) {
            log.debug("Text is null or empty, no chunking needed");
            return false;
        }
        
        // CRITICAL SAFETY CHECK: Force chunking for very large documents
        // This prevents the system from giving up and throwing "document too large" errors
        if (text.length() > 1_250_000) { // 1.25M characters (2.5x the previous 500k limit) = ~329k tokens
            log.warn("CRITICAL: Document has {} characters - FORCING CHUNKING to prevent failure!", text.length());
            return true;
        }
        
        // Use configurable thresholds
        int MAX_SINGLE_CHUNK_TOKENS = chunkingConfig.getMaxSingleChunkTokens();
        int MAX_SINGLE_CHUNK_CHARS = chunkingConfig.getMaxSingleChunkChars();
        
        // If aggressive chunking is enabled, use even lower limits
        if (chunkingConfig.isAggressiveChunking()) {
            MAX_SINGLE_CHUNK_TOKENS = Math.min(MAX_SINGLE_CHUNK_TOKENS, 30_000);
            MAX_SINGLE_CHUNK_CHARS = Math.min(MAX_SINGLE_CHUNK_CHARS, 120_000);
        }
        
        int estimatedTokens = tokenCounter.estimateTokens(text);
        boolean exceedsTokenLimit = estimatedTokens > MAX_SINGLE_CHUNK_TOKENS;
        boolean exceedsCharLimit = text.length() > MAX_SINGLE_CHUNK_CHARS;
        boolean needsChunking = exceedsTokenLimit || exceedsCharLimit;
        
        log.info("Document chunking check:");
        log.info("  - Characters: {}", text.length());
        log.info("  - Estimated tokens: {}", estimatedTokens);
        log.info("  - Max token limit: {}", MAX_SINGLE_CHUNK_TOKENS);
        log.info("  - Max char limit: {}", MAX_SINGLE_CHUNK_CHARS);
        log.info("  - Exceeds token limit: {}", exceedsTokenLimit);
        log.info("  - Exceeds char limit: {}", exceedsCharLimit);
        log.info("  - NEEDS CHUNKING: {}", needsChunking);
        
        if (!needsChunking && estimatedTokens > 10_000) {
            log.warn("WARNING: Large document ({} tokens) is NOT being chunked! This may cause issues!", estimatedTokens);
        }
        
        return needsChunking;
    }
    
    /**
     * Gets the estimated number of chunks for a document.
     * Uses token counting for more accurate estimation.
     */
    public int estimateChunkCount(String text) {
        if (!needsChunking(text)) {
            return 1;
        }
        
        // Estimate based on configured chunk size
        int totalTokens = tokenCounter.estimateTokens(text);
        int tokensPerChunk = chunkingConfig.getMaxSingleChunkTokens();
        int overlapTokens = chunkingConfig.getOverlapTokens();
        
        int effectiveTokensPerChunk = tokensPerChunk - overlapTokens;
        return (int) Math.ceil((double) totalTokens / effectiveTokensPerChunk);
    }
    
    /**
     * Creates a fallback node when the AI fails to generate structure for a chunk.
     * This ensures processing can continue even when the AI cannot identify clear structure.
     */
    private List<DocumentNode> createFallbackNode(DocumentChunker.DocumentChunk chunk, 
                                                 LlmClient.StructureOptions options, 
                                                 int chunkIndex, int totalChunks) {
        log.warn("Creating fallback node for chunk {} of {} with {} characters", 
            chunkIndex + 1, totalChunks, chunk.getLength());
        
        // Create a single fallback node for the entire chunk
        DocumentNode fallbackNode = new DocumentNode();
        fallbackNode.setType(DocumentNode.NodeType.CHAPTER);
        fallbackNode.setTitle("Chunk " + (chunkIndex + 1) + " Content");
        fallbackNode.setStartAnchor(chunk.getText().substring(0, Math.min(100, chunk.getLength())));
        fallbackNode.setEndAnchor(chunk.getText().substring(Math.max(0, chunk.getLength() - 100)));
        fallbackNode.setDepth((short) 0);
        fallbackNode.setAiConfidence(new BigDecimal("0.5")); // Low confidence since this is a fallback
        
        // Set offsets relative to the chunk
        fallbackNode.setStartOffset(0);
        fallbackNode.setEndOffset(chunk.getLength());
        
        return List.of(fallbackNode);
    }
    
    /**
     * Gets the chunking configuration for external access.
     */
    public DocumentChunkingConfig getChunkingConfig() {
        return chunkingConfig;
    }
}
