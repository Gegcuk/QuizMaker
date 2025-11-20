package uk.gegc.quizmaker.features.document.infra.converter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;
import uk.gegc.quizmaker.features.document.infra.util.ChunkTitleGenerator;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SizeBasedChunker implements UniversalChunker {

    private final SentenceBoundaryDetector sentenceBoundaryDetector;
    private final ChunkTitleGenerator titleGenerator;

    @Override
    public List<Chunk> chunkDocument(ConvertedDocument document, ProcessDocumentRequest request) {
        log.info("Starting size-based chunking for document: {} ({} characters)",
                document.getOriginalFilename(), document.getFullContent() != null ? document.getFullContent().length() : 0);

        String content = document.getFullContent();
        if (content == null || content.trim().isEmpty()) {
            log.warn("Document has no content, returning empty chunks");
            return new ArrayList<>();
        }

        int maxSize = request.getMaxChunkSize();

        List<Chunk> chunks;

        // If content fits in maxSize, create single chunk
        if (content.length() <= maxSize) {
            log.info("Document fits in single chunk: {} characters", content.length());
            int totalPages = document.getTotalPages() != null ? document.getTotalPages() : 1;
            String title = titleGenerator.generateDocumentChunkTitle(document.getTitle(), 0, 1);
            Chunk chunk = createChunk(title, content, 1, totalPages, 0, document);
            chunks = new ArrayList<>();
            chunks.add(chunk);
        } else {
            // Split recursively by size
            log.info("Document exceeds max size ({} > {}), splitting recursively", content.length(), maxSize);
            chunks = splitContentRecursively(content, document.getTitle(), request, 0, document, 0);
        }

        // Update chunk indices
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        // Final combination step to catch any remaining small chunks
        chunks = combineSmallChunks(chunks, request.getMinChunkSize() != null ? request.getMinChunkSize() : 300, request);

        log.info("Final chunking result: {} chunks", chunks.size());

        // Log chunk sizes for debugging
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            log.info("Final chunk {}: '{}' ({} chars)", i, chunk.getTitle(), chunk.getCharacterCount());
        }

        return chunks;
    }

    /**
     * Recursive middle-first chunking strategy using SentenceBoundaryDetector
     */
    private List<Chunk> splitContentRecursively(String content, String documentTitle,
                                                ProcessDocumentRequest request,
                                                int startChunkIndex, ConvertedDocument document,
                                                int recursionDepth) {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = request.getMaxChunkSize();

        // Absolute safety limit to prevent StackOverflowError (very unlikely to hit)
        // Use a high limit (1000) to handle very large documents, but still enforce size constraints
        if (recursionDepth > 1000) {
            log.error("Recursion depth exceeded safety limit (1000), forcing hard cuts at maxSize boundaries");
            // Force iterative splitting at exact maxSize boundaries to ensure size constraints
            return splitContentIteratively(content, documentTitle, request, startChunkIndex, document);
        }

        // If content is small enough, create single chunk
        if (content.length() <= maxSize) {
            int totalPages = document.getTotalPages() != null ? document.getTotalPages() : 1;
            String title = titleGenerator.generateDocumentChunkTitle(documentTitle, startChunkIndex, 1);
            Chunk chunk = createChunk(title, content, 1, totalPages, startChunkIndex, document);
            chunks.add(chunk);
            return chunks;
        }

        // When recursion is deep (but below safety limit), use simpler hard-cut strategy
        // to avoid expensive sentence boundary detection while still respecting maxSize
        boolean useHardCuts = recursionDepth > 50;
        
        int splitPoint;
        if (useHardCuts) {
            // Force split at maxSize boundary when recursion is deep
            log.debug("Deep recursion (depth={}), using hard cut at maxSize boundary", recursionDepth);
            splitPoint = maxSize;
        } else {
            // Use SentenceBoundaryDetector to find best split point
            splitPoint = sentenceBoundaryDetector.findBestSplitPoint(content, maxSize);
            
            // If no good split point found, use middle (but ensure it's <= maxSize)
            if (splitPoint <= 0 || splitPoint >= content.length()) {
                splitPoint = Math.min(maxSize, content.length() / 2);
            }
            
            // Ensure split point doesn't exceed maxSize (critical constraint)
            if (splitPoint > maxSize) {
                splitPoint = maxSize;
            }
        }

        // Create first chunk (guaranteed to be <= maxSize due to splitPoint constraint)
        String firstChunkContent = content.substring(0, splitPoint).trim();
        int estimatedTotalChunks = (int) Math.ceil((double) content.length() / maxSize);
        String firstChunkTitle = titleGenerator.generateDocumentChunkTitle(documentTitle, startChunkIndex, estimatedTotalChunks);
        
        int totalPages = document.getTotalPages() != null ? document.getTotalPages() : 1;
        Chunk firstChunk = createChunk(firstChunkTitle, firstChunkContent, 1, totalPages, startChunkIndex, document);
        chunks.add(firstChunk);

        // Recursively process remaining content
        String remainingContent = content.substring(splitPoint).trim();
        if (!remainingContent.isEmpty()) {
            List<Chunk> remainingChunks = splitContentRecursively(
                    remainingContent, documentTitle, request,
                    startChunkIndex + 1, document, recursionDepth + 1);
            chunks.addAll(remainingChunks);
        }

        // Fix chunk indices to ensure they are unique and sequential
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(startChunkIndex + i);
        }

        return chunks;
    }

    /**
     * Iterative splitting at exact maxSize boundaries (fallback for extremely deep recursion)
     * This ensures we never violate the maxSize constraint, even in edge cases
     */
    private List<Chunk> splitContentIteratively(String content, String documentTitle,
                                                ProcessDocumentRequest request,
                                                int startChunkIndex, ConvertedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = request.getMaxChunkSize();
        int totalPages = document.getTotalPages() != null ? document.getTotalPages() : 1;
        int chunkIndex = startChunkIndex;
        
        int offset = 0;
        int estimatedTotalChunks = (int) Math.ceil((double) content.length() / maxSize);
        
        while (offset < content.length()) {
            int endOffset = Math.min(offset + maxSize, content.length());
            String chunkContent = content.substring(offset, endOffset).trim();
            
            // Skip empty chunks
            if (chunkContent.isEmpty()) {
                offset = endOffset;
                continue;
            }
            
            String chunkTitle = titleGenerator.generateDocumentChunkTitle(documentTitle, chunkIndex, estimatedTotalChunks);
            Chunk chunk = createChunk(chunkTitle, chunkContent, 1, totalPages, chunkIndex, document);
            chunks.add(chunk);
            
            chunkIndex++;
            offset = endOffset;
        }
        
        log.warn("Iterative splitting created {} chunks at exact maxSize boundaries", chunks.size());
        return chunks;
    }

    /**
     * Combine small chunks with the next chunk
     * IMPORTANT: This method respects maxChunkSize constraint and never creates chunks exceeding it
     */
    private List<Chunk> combineSmallChunks(List<Chunk> chunks, int minSize, ProcessDocumentRequest request) {
        if (chunks.isEmpty()) return chunks;

        int maxSize = request.getMaxChunkSize();
        log.info("Combining chunks: {} chunks, minSize={}, maxSize={}", chunks.size(), minSize, maxSize);

        List<Chunk> result = new ArrayList<>();
        Chunk currentChunk = chunks.get(0);

        for (int i = 1; i < chunks.size(); i++) {
            Chunk nextChunk = chunks.get(i);

            // Calculate combined size including separator ("\n\n" = 2 chars)
            int separatorSize = 2;
            int combinedSize = currentChunk.getCharacterCount() + nextChunk.getCharacterCount() + separatorSize;

            // CRITICAL: Never combine if it would exceed maxChunkSize
            // This is the primary constraint that must be respected
            if (combinedSize > maxSize) {
                // Cannot combine without violating maxSize constraint
                result.add(currentChunk);
                currentChunk = nextChunk;
                continue;
            }

            // At this point, combining is safe from a size perspective
            // Now apply the combination logic for small chunks

            // Always combine if current chunk is very small (< 50% of minSize)
            boolean shouldCombine = currentChunk.getCharacterCount() < (minSize / 2);

            // Also combine if current chunk is below minimum size
            if (!shouldCombine && currentChunk.getCharacterCount() < minSize) {
                shouldCombine = true;
            }

            // Very aggressive: combine if current chunk is smaller than threshold
            int aggressiveThreshold = request.getAggressiveCombinationThreshold() != null ?
                    request.getAggressiveCombinationThreshold() : 5000; // Default to 5000 when null
            if (!shouldCombine && currentChunk.getCharacterCount() < aggressiveThreshold) {
                shouldCombine = true;
            }

            // Also combine if both chunks are small and combining gets closer to target size
            // Note: combinedSize is already verified to be <= maxSize above
            if (!shouldCombine && currentChunk.getCharacterCount() < minSize && nextChunk.getCharacterCount() < minSize) {
                shouldCombine = combinedSize > Math.max(currentChunk.getCharacterCount(), nextChunk.getCharacterCount());
            }

            if (shouldCombine) {
                // Combine chunks
                String combinedContent = currentChunk.getContent() + "\n\n" + nextChunk.getContent();
                String combinedTitle = currentChunk.getTitle() + " + " + nextChunk.getTitle();

                // Create combined chunk
                Chunk combinedChunk = new Chunk();
                combinedChunk.setTitle(combinedTitle.length() > 100 ? combinedTitle.substring(0, 97) + "..." : combinedTitle);
                combinedChunk.setContent(combinedContent);
                combinedChunk.setStartPage(currentChunk.getStartPage());
                combinedChunk.setEndPage(nextChunk.getEndPage());
                combinedChunk.setWordCount(currentChunk.getWordCount() + nextChunk.getWordCount());
                combinedChunk.setCharacterCount(combinedContent.length());
                combinedChunk.setChapterTitle(null);
                combinedChunk.setSectionTitle(null);
                combinedChunk.setDocumentTitle(currentChunk.getDocumentTitle());
                combinedChunk.setDocumentAuthor(currentChunk.getDocumentAuthor());
                combinedChunk.setConverterType(currentChunk.getConverterType());
                combinedChunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
                combinedChunk.setChunkIndex(currentChunk.getChunkIndex());

                currentChunk = combinedChunk;
            } else {
                // Keep current chunk and move to next
                result.add(currentChunk);
                currentChunk = nextChunk;
            }
        }

        // Add the last chunk
        result.add(currentChunk);

        // Update chunk indices
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setChunkIndex(i);
        }

        log.info("After combination: {} chunks", result.size());
        return result;
    }

    /**
     * Create a chunk with SIZE_BASED type
     */
    private Chunk createChunk(String title, String content, Integer startPage, Integer endPage,
                              int chunkIndex, ConvertedDocument document) {
        Chunk chunk = new Chunk();
        chunk.setTitle(title.length() > 100 ? title.substring(0, 97) + "..." : title);
        chunk.setContent(content);
        chunk.setStartPage(startPage);
        chunk.setEndPage(endPage);
        chunk.setWordCount(countWords(content));
        chunk.setCharacterCount(content.length());
        chunk.setChapterTitle(null);
        chunk.setSectionTitle(null);
        chunk.setChapterNumber(null);
        chunk.setSectionNumber(null);

        if (document != null) {
            chunk.setDocumentTitle(document.getTitle());
            chunk.setDocumentAuthor(document.getAuthor());
            chunk.setConverterType(document.getConverterType());
        } else {
            chunk.setDocumentTitle("Unknown Document");
            chunk.setDocumentAuthor("Unknown Author");
            chunk.setConverterType("Unknown");
        }

        chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        chunk.setChunkIndex(chunkIndex);
        return chunk;
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    @Override
    public ProcessDocumentRequest.ChunkingStrategy getSupportedStrategy() {
        return ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED;
    }

    @Override
    public boolean canHandle(ProcessDocumentRequest.ChunkingStrategy strategy) {
        return strategy == ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED ||
                strategy == ProcessDocumentRequest.ChunkingStrategy.AUTO;
    }
}

