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
        chunks = combineSmallChunks(chunks, request.getMinChunkSize() != null ? request.getMinChunkSize() : 1000, request);

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

        // Prevent infinite recursion
        if (recursionDepth > 10) {
            log.warn("Max recursion depth reached, creating single chunk");
            int totalPages = document.getTotalPages() != null ? document.getTotalPages() : 1;
            String title = titleGenerator.generateDocumentChunkTitle(documentTitle, startChunkIndex, 1);
            Chunk chunk = createChunk(title, content, 1, totalPages, startChunkIndex, document);
            chunks.add(chunk);
            return chunks;
        }

        // If content is small enough, create single chunk
        if (content.length() <= maxSize) {
            int totalPages = document.getTotalPages() != null ? document.getTotalPages() : 1;
            String title = titleGenerator.generateDocumentChunkTitle(documentTitle, startChunkIndex, 1);
            Chunk chunk = createChunk(title, content, 1, totalPages, startChunkIndex, document);
            chunks.add(chunk);
            return chunks;
        }

        // Use SentenceBoundaryDetector to find best split point
        int splitPoint = sentenceBoundaryDetector.findBestSplitPoint(content, maxSize);
        
        // If no good split point found, use middle
        if (splitPoint <= 0 || splitPoint >= content.length()) {
            splitPoint = Math.min(maxSize, content.length() / 2);
        }

        // Create first chunk
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
     * Combine small chunks with the next chunk
     */
    private List<Chunk> combineSmallChunks(List<Chunk> chunks, int minSize, ProcessDocumentRequest request) {
        if (chunks.isEmpty()) return chunks;

        log.info("Combining chunks: {} chunks, minSize={}", chunks.size(), minSize);

        List<Chunk> result = new ArrayList<>();
        Chunk currentChunk = chunks.get(0);

        for (int i = 1; i < chunks.size(); i++) {
            Chunk nextChunk = chunks.get(i);

            // More aggressive combination logic for tiny chunks
            int combinedSize = currentChunk.getCharacterCount() + nextChunk.getCharacterCount();

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
            if (!shouldCombine && currentChunk.getCharacterCount() < minSize && nextChunk.getCharacterCount() < minSize) {
                shouldCombine = combinedSize <= 100000 && combinedSize > Math.max(currentChunk.getCharacterCount(), nextChunk.getCharacterCount());
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

