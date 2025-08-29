package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for chunking large documents into manageable pieces for AI processing.
 * Handles semantic boundaries and overlapping to maintain context.
 */
@Service
@Slf4j
public class DocumentChunker {

    public static final int MAX_CHUNK_SIZE = 250_000; // Leave buffer for prompts
    public static final int OVERLAP_SIZE = 10_000; // Prevent context loss
    private static final int MIN_CHUNK_SIZE = 50_000; // Minimum meaningful chunk
    
    // Patterns to identify semantic boundaries
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SECTION_BREAK = Pattern.compile("\\n\\s*[A-Z][A-Z\\s]+\\n");
    private static final Pattern CHAPTER_BREAK = Pattern.compile("\\n\\s*Chapter\\s+\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * Chunks a large document into manageable pieces for AI processing.
     * 
     * @param text the full document text
     * @param documentId the document ID for logging
     * @return list of document chunks with metadata
     */
    public List<DocumentChunk> chunkDocument(String text, String documentId) {
        log.info("Chunking document {} with {} characters", documentId, text.length());
        
        if (text.length() <= MAX_CHUNK_SIZE) {
            // Document is small enough to process in one chunk
            return List.of(new DocumentChunk(text, 0, text.length(), 0));
        }
        
        List<DocumentChunk> chunks = new ArrayList<>();
        int currentPosition = 0;
        int chunkIndex = 0;
        
        while (currentPosition < text.length()) {
            int chunkEnd = calculateChunkEnd(text, currentPosition);
            
            // Extract chunk text
            String chunkText = text.substring(currentPosition, chunkEnd);
            
            // Create chunk with metadata
            DocumentChunk chunk = new DocumentChunk(
                chunkText, 
                currentPosition, 
                chunkEnd, 
                chunkIndex++
            );
            
            chunks.add(chunk);
            log.debug("Created chunk {}: positions {} to {} ({} chars)", 
                chunkIndex, currentPosition, chunkEnd, chunkText.length());
            
            // Move to next chunk position (with overlap)
            currentPosition = Math.max(currentPosition + 1, chunkEnd - OVERLAP_SIZE);
        }
        
        log.info("Document {} chunked into {} pieces", documentId, chunks.size());
        return chunks;
    }
    
    /**
     * Calculates the optimal end position for a chunk, respecting semantic boundaries.
     */
    private int calculateChunkEnd(String text, int startPosition) {
        int maxEnd = Math.min(startPosition + MAX_CHUNK_SIZE, text.length());
        
        // If we're near the end of the document, just use the end
        if (maxEnd >= text.length() - MIN_CHUNK_SIZE) {
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
        return position;
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
}
