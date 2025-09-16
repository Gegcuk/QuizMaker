package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for chunking large documents into manageable pieces for AI processing.
 * Handles semantic boundaries and overlapping to maintain context.
 * Uses token counting to ensure chunks don't exceed model limits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentChunker {
    
    private final TokenCounter tokenCounter;
    private final DocumentChunkingConfig chunkingConfig;
    
    // Minimum chunk size in tokens (not configurable, as it's a hard minimum)
    private static final int MIN_CHUNK_TOKENS = 5_000;
    
    // These will be calculated dynamically when needed
    private Integer maxChunkSizeCache = null;
    private Integer overlapSizeCache = null;
    
    // Patterns to identify semantic boundaries
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SECTION_BREAK = Pattern.compile("\\n\\s*[A-Z][A-Z\\s]+\\n");
    private static final Pattern CHAPTER_BREAK = Pattern.compile("\\n\\s*Chapter\\s+\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * Get max chunk size in characters based on token limits.
     * Calculated lazily after dependency injection.
     */
    private int getMaxChunkSize() {
        if (maxChunkSizeCache == null) {
            maxChunkSizeCache = tokenCounter.getConfiguredSafeChunkSize();
            log.info("Calculated max chunk size: {} characters using configured settings", maxChunkSizeCache);
        }
        return maxChunkSizeCache;
    }
    
    /**
     * Get overlap size in characters based on token count.
     * Calculated lazily after dependency injection.
     */
    private int getOverlapSize() {
        if (overlapSizeCache == null) {
            int overlapTokens = chunkingConfig.getOverlapTokens();
            overlapSizeCache = tokenCounter.estimateMaxCharsForTokens(overlapTokens);
            log.info("Calculated overlap size: {} characters for {} overlap tokens", 
                    overlapSizeCache, overlapTokens);
        }
        return overlapSizeCache;
    }
    
    /**
     * Chunks a large document into manageable pieces for AI processing.
     * Uses token counting to ensure chunks don't exceed model limits.
     * 
     * @param text the full document text
     * @param documentId the document ID for logging
     * @return list of document chunks with metadata
     */
    public List<DocumentChunk> chunkDocument(String text, String documentId) {
        if (text == null || text.isEmpty()) {
            log.warn("Received null or empty text for chunking");
            return List.of(new DocumentChunk("", 0, 0, 0));
        }
        
        int estimatedTokens = tokenCounter.estimateTokens(text);
        log.info("Starting document chunking for document {}", documentId);
        log.info("  - Input size: {} characters", text.length());
        log.info("  - Estimated tokens: {}", estimatedTokens);
        log.info("  - Max tokens per chunk: {}", chunkingConfig.getMaxSingleChunkTokens());
        log.info("  - Aggressive chunking: {}", chunkingConfig.isAggressiveChunking());
        
        // Filter out irrelevant content like indexes, appendices, etc.
        String filteredText = filterIrrelevantContent(text);
        int filteredTokens = tokenCounter.estimateTokens(filteredText);
        log.info("Filtered document {} from {} to {} characters (~{} tokens)", 
                documentId, text.length(), filteredText.length(), filteredTokens);
        
        // Get max chunk size (calculated lazily)
        int maxChunkSize = getMaxChunkSize();
        log.info("Max chunk size (chars): {}", maxChunkSize);
        
        // Use configured limits - prioritize token limits over character limits
        int safeTokenLimit = chunkingConfig.getMaxSingleChunkTokens();
        int safeCharLimit = chunkingConfig.getMaxSingleChunkChars();
        
        // Primary check: if within token limits, process as single chunk
        // Character limit is only a fallback safety check
        if (!tokenCounter.exceedsTokenLimit(filteredText, safeTokenLimit)) {
            // Document is within token limits - process as single chunk
            log.info("Document {} fits in single chunk ({} tokens, {} chars)", 
                    documentId, filteredTokens, filteredText.length());
            return List.of(new DocumentChunk(filteredText, 0, filteredText.length(), 0));
        }
        
        // Secondary safety check: if character limit is exceeded by a large margin, warn but still chunk
        if (filteredText.length() > safeCharLimit * 2) {
            log.warn("Document {} exceeds character limit by large margin ({} chars > {} chars), but will be chunked by token count", 
                    documentId, filteredText.length(), safeCharLimit * 2);
        }
        
        log.info("Document {} requires chunking ({} tokens, {} chars)", 
                documentId, filteredTokens, filteredText.length());
        
        List<DocumentChunk> chunks = new ArrayList<>();
        int currentPosition = 0;
        int chunkIndex = 0;
        int loopCount = 0;
        int maxLoops = filteredText.length() / 1000 + 100; // Prevent infinite loops
        
        while (currentPosition < filteredText.length()) {
            loopCount++;
            if (loopCount > maxLoops) {
                log.error("CRITICAL: Too many chunking loops ({}), forcing emergency chunking", loopCount);
                return forceChunkDocument(text, documentId);
            }
            int chunkEnd = calculateChunkEnd(filteredText, currentPosition, maxChunkSize);
            
            // Safety check: ensure we're actually advancing
            if (chunkEnd <= currentPosition) {
                log.error("Chunking failed: chunk end ({}) <= current position ({}). Forcing advancement.", 
                    chunkEnd, currentPosition);
                chunkEnd = Math.min(currentPosition + maxChunkSize, filteredText.length());
                
                // CRITICAL: If we still can't advance, force a minimum advancement
                if (chunkEnd <= currentPosition) {
                    chunkEnd = Math.min(currentPosition + 1000, filteredText.length());
                    log.error("CRITICAL: Forced chunk end to {} to prevent infinite loop", chunkEnd);
                }
            }
            
            // Extract chunk text
            String chunkText = filteredText.substring(currentPosition, chunkEnd);
            
            // Skip chunks that are too small or contain only irrelevant content
            int chunkTokens = tokenCounter.estimateTokens(chunkText);
            if (chunkTokens < MIN_CHUNK_TOKENS || isIrrelevantChunk(chunkText)) {
                currentPosition = chunkEnd;
                continue;
            }
            
            // Create chunk with metadata
            DocumentChunk chunk = new DocumentChunk(
                chunkText, 
                currentPosition, 
                chunkEnd, 
                chunkIndex++
            );
            
            chunks.add(chunk);

            // Move to next chunk position (with overlap)
            int overlapSize = getOverlapSize();
            int nextPosition = Math.max(currentPosition + 1, chunkEnd - overlapSize);
            
            // CRITICAL FIX: Ensure we're advancing by at least a reasonable amount
            if (nextPosition <= currentPosition) {
                log.error("Chunking failed: next position ({}) <= current position ({}). Forcing advancement.", 
                    nextPosition, currentPosition);
                nextPosition = currentPosition + maxChunkSize / 2; // Force significant advancement
            }
            
            // ADDITIONAL SAFETY: If we're advancing by less than 10% of the chunk size, force more advancement
            int advancement = nextPosition - currentPosition;
            if (advancement < maxChunkSize / 10) {
                log.warn("Chunking advancing too slowly: {} chars. Forcing advancement.", advancement);
                nextPosition = currentPosition + (maxChunkSize * 4) / 5; // Force 80% advancement
            }
            
            currentPosition = nextPosition;
        }
        
        log.info("Document {} chunked into {} pieces", documentId, chunks.size());
        
        // Safety check: if we have a large document but only one chunk, something went wrong
        if (chunks.size() == 1 && estimatedTokens > chunkingConfig.getMaxSingleChunkTokens()) {
            log.error("ERROR: Large document ({} tokens) was not properly chunked! Only 1 chunk created.", estimatedTokens);
            
            if (chunkingConfig.isEnableEmergencyChunking()) {
                log.warn("Forcing emergency chunking to prevent token limit issues");
                return forceChunkDocument(text, documentId);
            } else {
                log.error("Emergency chunking is disabled. Document may fail to process!");
            }
        }
        
        return chunks;
    }
    
    /**
     * Calculates the optimal end position for a chunk, respecting semantic boundaries and token limits.
     */
    private int calculateChunkEnd(String text, int startPosition, int maxChunkSize) {
        int maxEnd = Math.min(startPosition + maxChunkSize, text.length());
        
        // If we're near the end of the document, just use the end
        int minChunkSize = tokenCounter.estimateMaxCharsForTokens(MIN_CHUNK_TOKENS);
        if (maxEnd >= text.length() - minChunkSize) {
            return text.length();
        }
        
        // Look for semantic boundaries within the chunk
        String chunkText = text.substring(startPosition, maxEnd);
        
        // Try to find a good breaking point
        int bestBreak = findBestBreakPoint(chunkText);
        
        if (bestBreak > 0) {
            return startPosition + bestBreak;
        }
        
        // Fallback: break at word boundary
        return findWordBoundary(text, maxEnd);
    }
    
    /**
     * Finds the best semantic breaking point within a chunk.
     */
    private int findBestBreakPoint(String chunkText) {
        // Look for paragraph breaks first
        Matcher paragraphMatcher = PARAGRAPH_BREAK.matcher(chunkText);
        int lastParagraphBreak = -1;
        while (paragraphMatcher.find()) {
            lastParagraphBreak = paragraphMatcher.end();
        }
        
        // Look for section breaks
        Matcher sectionMatcher = SECTION_BREAK.matcher(chunkText);
        int lastSectionBreak = -1;
        while (sectionMatcher.find()) {
            lastSectionBreak = sectionMatcher.end();
        }
        
        // Look for chapter breaks
        Matcher chapterMatcher = CHAPTER_BREAK.matcher(chunkText);
        int lastChapterBreak = -1;
        while (chapterMatcher.find()) {
            lastChapterBreak = chapterMatcher.end();
        }
        
        // Prefer chapter breaks, then section breaks, then paragraph breaks
        if (lastChapterBreak > chunkText.length() * 0.7) {
            return lastChapterBreak;
        }
        if (lastSectionBreak > chunkText.length() * 0.7) {
            return lastSectionBreak;
        }
        if (lastParagraphBreak > chunkText.length() * 0.7) {
            return lastParagraphBreak;
        }
        
        return -1; // No good break point found
    }
    
    /**
     * Filters out irrelevant content like indexes, appendices, etc.
     */
    private String filterIrrelevantContent(String text) {
        // Find the start of irrelevant sections
        String[] irrelevantPatterns = {
            "\\bINDEX\\b",
            "\\bAPPENDIX\\b",
            "\\bAPPENDICES\\b", 
            "\\bBIBLIOGRAPHY\\b",
            "\\bREFERENCES\\b",
            "\\bGLOSSARY\\b",
            "\\bACKNOWLEDGMENTS\\b",
            "\\bACKNOWLEDGEMENTS\\b",
            "\\bABOUT THE AUTHOR\\b",
            "\\bABOUT THE AUTHORS\\b"
        };
        
        int earliestIrrelevantStart = text.length();
        for (String pattern : irrelevantPatterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find() && m.start() < earliestIrrelevantStart) {
                earliestIrrelevantStart = m.start();
            }
        }
        
        // If we found irrelevant content, truncate the text
        if (earliestIrrelevantStart < text.length()) {
            log.info("Filtering out irrelevant content starting at position {}", earliestIrrelevantStart);
            return text.substring(0, earliestIrrelevantStart).trim();
        }
        
        return text;
    }
    
    /**
     * Checks if a chunk contains only irrelevant content.
     */
    private boolean isIrrelevantChunk(String chunkText) {
        // Check if chunk is mostly index-like content
        String[] irrelevantKeywords = {
            "index", "appendix", "bibliography", "references", "glossary", 
            "acknowledgment", "about the author", "page", "chapter"
        };
        
        String lowerChunk = chunkText.toLowerCase();
        int irrelevantCount = 0;
        int totalWords = lowerChunk.split("\\s+").length;
        
        for (String keyword : irrelevantKeywords) {
            if (lowerChunk.contains(keyword)) {
                irrelevantCount++;
            }
        }
        
        // If more than 30% of words are irrelevant keywords, consider it irrelevant
        return irrelevantCount > 0 && (double) irrelevantCount / totalWords > 0.3;
    }
    
    /**
     * Finds a word boundary near the given position.
     */
    private int findWordBoundary(String text, int position) {
        // Look backwards for a word boundary
        for (int i = position; i > position - 1000; i--) {
            if (i <= 0) return 0;
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        // If no word boundary found, force a significant advancement
        // This prevents the 1-character advancement bug
        // Force at least 50% advancement to prevent infinite loops
        int forcedPosition = Math.max(position - 1000, position / 2);
        
        // CRITICAL FIX: Ensure we always advance forward
        if (forcedPosition <= 0) {
            forcedPosition = Math.min(1000, text.length() / 2);
        }
        
        // Additional safety: if we're still too close to the original position, force more advancement
        if (forcedPosition > position * 0.8) {
            forcedPosition = position / 2;
        }
        
        // FINAL SAFETY: Ensure we never return a position that doesn't advance
        if (forcedPosition <= 0) {
            forcedPosition = Math.min(1000, text.length());
        }
        
        return forcedPosition;
    }
    
    /**
     * Represents a chunk of document text with metadata.
     */
    public static class DocumentChunk {
        private final String text;
        private final int startOffset;
        private final int endOffset;
        private final int chunkIndex;
        
        public DocumentChunk(String text, int startOffset, int endOffset, int chunkIndex) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.chunkIndex = chunkIndex;
        }
        
        public String getText() { return text; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
        public int getChunkIndex() { return chunkIndex; }
        public int getLength() { return text.length(); }
        
        @Override
        public String toString() {
            return String.format("Chunk[%d: %d-%d, %d chars]", 
                chunkIndex, startOffset, endOffset, text.length());
        }
    }
    
    /**
     * Emergency fallback: Force chunk a document by splitting it into fixed-size pieces.
     * This is used when normal chunking fails.
     */
    private List<DocumentChunk> forceChunkDocument(String text, String documentId) {
        log.warn("Using emergency forced chunking for document {}", documentId);
        
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkSize = chunkingConfig.getMaxSingleChunkChars(); // Use configured size
        int overlap = tokenCounter.estimateMaxCharsForTokens(chunkingConfig.getOverlapTokens());
        int position = 0;
        int chunkIndex = 0;
        
        while (position < text.length()) {
            int end = Math.min(position + chunkSize, text.length());
            String chunkText = text.substring(position, end);
            
            chunks.add(new DocumentChunk(chunkText, position, end, chunkIndex++));
            log.info("Forced chunk {}: {} chars at position {}-{}", 
                    chunkIndex, chunkText.length(), position, end);
            
            position = Math.max(position + 1, end - overlap);
            
            // Safety: prevent infinite loop
            if (position <= end - chunkSize + 1000) {
                position = end - overlap / 2;
            }
        }
        
        log.info("Emergency chunking complete: {} chunks created", chunks.size());
        return chunks;
    }
}
