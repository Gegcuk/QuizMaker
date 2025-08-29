package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final NodeMerger nodeMerger;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    /**
     * Processes a large document by chunking it and merging the results.
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
            // Small document, process normally
            log.info("Document {} is small enough for single-chunk processing", documentId);
            return llmClient.generateStructure(text, options);
        }
        
        // Step 2: Process chunks in parallel
        List<List<DocumentNode>> chunkResults = processChunksParallel(chunks, options, documentId);
        
        // Step 3: Merge results
        List<DocumentNode> mergedNodes = nodeMerger.mergeChunkNodes(chunkResults, chunks);
        
        log.info("Successfully processed large document {}: {} chunks -> {} final nodes", 
            documentId, chunks.size(), mergedNodes.size());
        
        return mergedNodes;
    }
    
    /**
     * Processes document chunks in parallel for better performance.
     */
    private List<List<DocumentNode>> processChunksParallel(List<DocumentChunker.DocumentChunk> chunks, 
                                                         LlmClient.StructureOptions options, 
                                                         String documentId) {
        List<CompletableFuture<List<DocumentNode>>> futures = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            final int chunkIndex = i;
            
            CompletableFuture<List<DocumentNode>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    log.debug("Processing chunk {} of document {} ({} chars)", 
                        chunkIndex, documentId, chunk.getLength());
                    
                    List<DocumentNode> nodes = llmClient.generateStructure(chunk.getText(), options);
                    
                    log.debug("Chunk {} completed with {} nodes", chunkIndex, nodes.size());
                    return nodes;
                    
                } catch (Exception e) {
                    log.error("Failed to process chunk {} of document {}", chunkIndex, documentId, e);
                    throw new RuntimeException("Chunk processing failed", e);
                }
            }, executorService);
            
            futures.add(future);
        }
        
        // Wait for all chunks to complete
        try {
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            // Wait with timeout
            allFutures.get(30, TimeUnit.MINUTES); // 30 minute timeout for large documents
            
            // Collect results
            List<List<DocumentNode>> results = new ArrayList<>();
            for (CompletableFuture<List<DocumentNode>> future : futures) {
                results.add(future.get());
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to process chunks for document {}", documentId, e);
            throw new RuntimeException("Chunked processing failed", e);
        }
    }
    
    /**
     * Processes document chunks sequentially (fallback for debugging).
     */
    private List<List<DocumentNode>> processChunksSequential(List<DocumentChunker.DocumentChunk> chunks, 
                                                           LlmClient.StructureOptions options, 
                                                           String documentId) {
        List<List<DocumentNode>> results = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            
            log.debug("Processing chunk {} of document {} ({} chars)", 
                i, documentId, chunk.getLength());
            
            try {
                List<DocumentNode> nodes = llmClient.generateStructure(chunk.getText(), options);
                results.add(nodes);
                
                log.debug("Chunk {} completed with {} nodes", i, nodes.size());
                
            } catch (Exception e) {
                log.error("Failed to process chunk {} of document {}", i, documentId, e);
                throw new RuntimeException("Chunk processing failed", e);
            }
        }
        
        return results;
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
