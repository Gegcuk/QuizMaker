package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

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
     * Determines if a document needs chunking based on its size.
     */
    public boolean needsChunking(String text) {
        return text.length() > DocumentChunker.MAX_CHUNK_SIZE;
    }
    
    /**
     * Gets the estimated number of chunks for a document.
     */
    public int estimateChunkCount(String text) {
        if (!needsChunking(text)) {
            return 1;
        }
        
        int effectiveChunkSize = DocumentChunker.MAX_CHUNK_SIZE - DocumentChunker.OVERLAP_SIZE;
        return (int) Math.ceil((double) text.length() / effectiveChunkSize);
    }
}
