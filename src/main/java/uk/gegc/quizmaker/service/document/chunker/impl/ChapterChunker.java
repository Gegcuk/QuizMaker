package uk.gegc.quizmaker.service.document.chunker.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.chunker.UniversalChunker;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;
import uk.gegc.quizmaker.util.ChunkTitleGenerator;
import uk.gegc.quizmaker.util.SentenceBoundaryDetector;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChapterChunker implements UniversalChunker {

    private final SentenceBoundaryDetector sentenceBoundaryDetector;
    private final ChunkTitleGenerator titleGenerator;

    @Override
    public List<Chunk> chunkDocument(ConvertedDocument document, ProcessDocumentRequest request) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        log.info("Starting universal chapter-based chunking for document: {} ({} characters, {} chapters)",
                document.getOriginalFilename(), document.getFullContent().length(), document.getChapters().size());

        if (!document.getChapters().isEmpty()) {
            // Phase 1: Split by chapters first
            List<Chunk> chapterChunks = splitDocumentByChapters(document, request);
            
            // Phase 2: Optimize chapter chunks for size
            chunks = optimizeChapterChunks(chapterChunks, request);
            
        } else {
            // No chapters detected, treat entire document as one chapter and apply two-phase logic
            log.info("No chapters detected, treating entire document as single chapter");
            
            // Phase 1: Create single document chunk
            List<Chunk> documentChunks = new ArrayList<>();
            Chunk documentChunk = createChunk(
                    "Document", document.getFullContent(),
                    1, document.getTotalPages() != null ? document.getTotalPages() : 1,
                    "Document", null,
                    0, ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED,
                    document
            );
            documentChunks.add(documentChunk);
            
            // Phase 2: Optimize document chunk for size
            chunks = optimizeChapterChunks(documentChunks, request);
        }

        // Update chunk indices
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        // Final combination step to catch any remaining small chunks
        int minChunkSize = request.getMinChunkSize() != null ? request.getMinChunkSize() : 1000;
        chunks = combineSmallChunks(chunks, minChunkSize, request);

        log.info("Final chunking result: {} chunks", chunks.size());
        
        // Log chunk sizes for debugging
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            log.info("Final chunk {}: '{}' ({} chars)", i, chunk.getTitle(), chunk.getCharacterCount());
        }
        
        return chunks;
    }

    /**
     * Phase 1: Split document by chapters
     */
    private List<Chunk> splitDocumentByChapters(ConvertedDocument document, ProcessDocumentRequest request) {
        List<Chunk> chapterChunks = new ArrayList<>();
        int chunkIndex = 0;

        for (ConvertedDocument.Chapter chapter : document.getChapters()) {
            String chapterContent = chapter.getContent();
            
            // Handle null or empty chapter content
            if (chapterContent == null || chapterContent.trim().isEmpty()) {
                log.warn("Chapter '{}' has no content, checking sections", chapter.getTitle());
                
                // If chapter has no content but has sections, process the sections
                if (!chapter.getSections().isEmpty()) {
                    for (ConvertedDocument.Section section : chapter.getSections()) {
                        chapterChunks.addAll(splitSectionRespectingBoundaries(section, chapter, document, request, chunkIndex));
                        chunkIndex += chapterChunks.size();
                    }
                }
                continue;
            }

            // Create chapter chunk
            Chunk chapterChunk = createChunk(
                    chapter.getTitle(), chapterContent,
                    chapter.getStartPage(), chapter.getEndPage(),
                    chapter.getTitle(), null,
                    chunkIndex, ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED,
                    document
            );
            chapterChunks.add(chapterChunk);
            chunkIndex++;
        }

        log.info("Phase 1 complete: {} chapter chunks created", chapterChunks.size());
        return chapterChunks;
    }

    /**
     * Phase 2: Optimize chapter chunks for size
     */
    private List<Chunk> optimizeChapterChunks(List<Chunk> chapterChunks, ProcessDocumentRequest request) {
        if (chapterChunks.isEmpty()) {
            return chapterChunks;
        }

        List<Chunk> optimizedChunks = new ArrayList<>();
        int maxSize = request.getMaxChunkSize();
        int minSize = request.getMinChunkSize() != null ? request.getMinChunkSize() : 1000;

        log.info("Phase 2: Optimizing {} chapter chunks (maxSize: {}, minSize: {})", 
                chapterChunks.size(), maxSize, minSize);

        for (int i = 0; i < chapterChunks.size(); i++) {
            Chunk currentChunk = chapterChunks.get(i);
            int currentSize = currentChunk.getCharacterCount();

            log.debug("Processing chapter chunk {}: '{}' ({} chars)", 
                    i, currentChunk.getTitle(), currentSize);

            // Case 1: Chapter is too large - split it
            if (currentSize > maxSize) {
                log.info("Chapter '{}' is too large ({} chars), splitting", currentChunk.getTitle(), currentSize);
                List<Chunk> splitChunks = splitContentBySize(
                        currentChunk.getContent(), 
                        currentChunk.getTitle(),
                        currentChunk.getStartPage(), 
                        currentChunk.getEndPage(), 
                        request, 
                        optimizedChunks.size(), 
                        null // We don't have the full document context here, but createChunk handles null
                );
                optimizedChunks.addAll(splitChunks);
            }
            // Case 2: Chapter is too small - try to combine with next chapter
            else if (currentSize < minSize && i < chapterChunks.size() - 1) {
                Chunk nextChunk = chapterChunks.get(i + 1);
                int combinedSize = currentSize + nextChunk.getCharacterCount();
                
                log.info("Chapter '{}' is too small ({} chars), combining with next chapter '{}' ({} chars) = {} chars", 
                        currentChunk.getTitle(), currentSize, 
                        nextChunk.getTitle(), nextChunk.getCharacterCount(), combinedSize);

                if (combinedSize <= maxSize) {
                    // Combine chunks
                    String combinedContent = currentChunk.getContent() + "\n\n" + nextChunk.getContent();
                    String combinedTitle = currentChunk.getTitle() + " + " + nextChunk.getTitle();
                    
                    // Create a minimal document context for the combined chunk
                    ConvertedDocument combinedDoc = new ConvertedDocument();
                    combinedDoc.setTitle(currentChunk.getDocumentTitle());
                    combinedDoc.setAuthor(currentChunk.getDocumentAuthor());
                    combinedDoc.setConverterType(currentChunk.getConverterType());
                    
                    Chunk combinedChunk = createChunk(
                            combinedTitle, combinedContent,
                            currentChunk.getStartPage(), nextChunk.getEndPage(),
                            currentChunk.getChapterTitle(), null,
                            optimizedChunks.size(), ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED,
                            combinedDoc
                    );
                    optimizedChunks.add(combinedChunk);
                    i++; // Skip next chunk since we combined it
                } else {
                    // Can't combine, keep current chunk as-is
                    log.info("Cannot combine chapters ({} chars > {}), keeping separate", combinedSize, maxSize);
                    optimizedChunks.add(currentChunk);
                }
            }
            // Case 3: Chapter is just right - keep as-is
            else {
                log.debug("Chapter '{}' size is good ({} chars), keeping as-is", currentChunk.getTitle(), currentSize);
                optimizedChunks.add(currentChunk);
            }
        }

        log.info("Phase 2 complete: {} optimized chunks created", optimizedChunks.size());
        return optimizedChunks;
    }

    /**
     * Split chapter respecting boundaries - keep entire chapter if possible
     */
    private List<Chunk> splitChapterRespectingBoundaries(ConvertedDocument.Chapter chapter,
                                                         ConvertedDocument document,
                                                         ProcessDocumentRequest request,
                                                         int startChunkIndex) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = startChunkIndex;

        String chapterContent = chapter.getContent();
        int maxSize = request.getMaxChunkSize();

        // Handle null or empty chapter content
        if (chapterContent == null || chapterContent.trim().isEmpty()) {
            log.warn("Chapter '{}' has no content, checking sections", chapter.getTitle());

            // If chapter has no content but has sections, process the sections
            if (!chapter.getSections().isEmpty()) {
                log.info("Chapter has {} sections, processing sections", chapter.getSections().size());
                for (ConvertedDocument.Section section : chapter.getSections()) {
                    chunks.addAll(splitSectionRespectingBoundaries(section, chapter, document, request, chunkIndex));
                    chunkIndex = startChunkIndex + chunks.size();
                }
                return chunks;
            } else {
                log.warn("Chapter '{}' has no content and no sections, skipping", chapter.getTitle());
                return chunks;
            }
        }

        log.info("Processing chapter: '{}', content length: {}, maxSize: {}",
                chapter.getTitle(), chapterContent.length(), maxSize);

        if (chapterContent.length() <= maxSize) {
            // Entire chapter fits into one chunk
            Chunk chunk = createChunk(
                    chapter.getTitle(), chapterContent,
                    chapter.getStartPage(), chapter.getEndPage(),
                    chapter.getTitle(), null,
                    chunkIndex, ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED,
                    document
            );
            chunks.add(chunk);
            log.info("Chapter fits in single chunk: {} characters", chapterContent.length());
            return chunks;
        }

        // Chapter too big, try sections first
        if (!chapter.getSections().isEmpty()) {
            log.info("Chapter has {} sections, splitting by sections", chapter.getSections().size());
            for (ConvertedDocument.Section section : chapter.getSections()) {
                chunks.addAll(splitSectionRespectingBoundaries(section, chapter, document, request, chunkIndex));
                chunkIndex = startChunkIndex + chunks.size();
            }
        } else {
            // No sections, split by sentences within chapter
            log.info("Chapter has no sections, splitting by size with sentence boundaries");
            chunks.addAll(splitContentBySize(chapterContent, chapter.getTitle(),
                    chapter.getStartPage(), chapter.getEndPage(), request, chunkIndex, document));
        }

        return chunks;
    }

    /**
     * Split section respecting boundaries - keep entire section if possible
     */
    private List<Chunk> splitSectionRespectingBoundaries(ConvertedDocument.Section section,
                                                         ConvertedDocument.Chapter chapter,
                                                         ConvertedDocument document,
                                                         ProcessDocumentRequest request,
                                                         int startChunkIndex) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = startChunkIndex;

        String sectionContent = section.getContent();
        int maxSize = request.getMaxChunkSize();

        // Handle null or empty section content
        if (sectionContent == null || sectionContent.trim().isEmpty()) {
            log.warn("Section '{}' has no content, skipping", section.getTitle());
            return chunks;
        }

        log.info("Processing section: '{}', content length: {}, maxSize: {}",
                section.getTitle(), sectionContent.length(), maxSize);

        if (sectionContent.length() <= maxSize) {
            // Entire section fits into one chunk
            Chunk chunk = createChunk(
                    section.getTitle(), sectionContent,
                    section.getStartPage(), section.getEndPage(),
                    chapter.getTitle(), section.getTitle(),
                    chunkIndex, ProcessDocumentRequest.ChunkingStrategy.SECTION_BASED,
                    document
            );
            chunks.add(chunk);
            log.info("Section fits in single chunk: {} characters", sectionContent.length());
            return chunks;
        }

        // Section too big, split by size with enhanced boundary detection
        log.info("Section too large, splitting by size with enhanced boundary detection");
        chunks.addAll(splitContentBySize(sectionContent, section.getTitle(),
                section.getStartPage(), section.getEndPage(), request, chunkIndex, document));

        return chunks;
    }

    /**
     * Split content by size with sentence boundaries, maintaining chapter context
     */
    private List<Chunk> splitContentBySize(String content, String title,
                                           Integer startPage, Integer endPage,
                                           ProcessDocumentRequest request,
                                           int startChunkIndex, ConvertedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = request.getMaxChunkSize();
        int minSize = request.getMinChunkSize() != null ? request.getMinChunkSize() : 1000;
        int aggressiveThreshold = request.getAggressiveCombinationThreshold() != null ? 
                request.getAggressiveCombinationThreshold() : 3000;

        log.info("Splitting content: title='{}', totalLength={}, maxSize={}, minSize={}, aggressiveThreshold={}", 
                title, content.length(), maxSize, minSize, aggressiveThreshold);

        // Use recursive middle-first approach
        chunks = splitContentRecursively(content, title, startPage, endPage, request, startChunkIndex, document, 0);

        log.info("Total chunks created: {}", chunks.size());
        
        // Handle tiny final chunk by combining with previous chunk if needed
        if (chunks.size() > 1) {
            Chunk finalChunk = chunks.get(chunks.size() - 1);
            
            if (finalChunk.getCharacterCount() < minSize) {
                log.info("Final chunk is too small ({} chars < {}), combining with previous chunk", 
                        finalChunk.getCharacterCount(), minSize);
                
                Chunk previousChunk = chunks.get(chunks.size() - 2);
                String combinedContent = previousChunk.getContent() + "\n\n" + finalChunk.getContent();
                String combinedTitle = previousChunk.getTitle() + " + " + finalChunk.getTitle();
                
                // Create combined chunk
                Chunk combinedChunk = createChunk(
                        combinedTitle, combinedContent,
                        previousChunk.getStartPage(), finalChunk.getEndPage(),
                        previousChunk.getChapterTitle(), previousChunk.getSectionTitle(),
                        previousChunk.getChunkIndex(), ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, document
                );
                
                // Replace last two chunks with combined chunk
                chunks.set(chunks.size() - 2, combinedChunk);
                chunks.remove(chunks.size() - 1);
                
                log.info("Combined final chunks: {} characters", combinedContent.length());
            }
        }
        
        return chunks;
    }

    /**
     * Recursive middle-first chunking strategy
     */
    private List<Chunk> splitContentRecursively(String content, String title,
                                               Integer startPage, Integer endPage,
                                               ProcessDocumentRequest request,
                                               int startChunkIndex, ConvertedDocument document,
                                               int recursionDepth) {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = request.getMaxChunkSize();
        int minSize = request.getMinChunkSize() != null ? request.getMinChunkSize() : 1000;
        int aggressiveThreshold = request.getAggressiveCombinationThreshold() != null ? 
                request.getAggressiveCombinationThreshold() : 5000; // Raised from 3000 to 5000

        // Prevent infinite recursion
        if (recursionDepth > 10) {
            log.warn("Max recursion depth reached, creating single chunk");
            Chunk chunk = createChunk(title, content, startPage, endPage,
                    null, title, startChunkIndex, ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, document);
            chunks.add(chunk);
            return chunks;
        }

        log.debug("Recursion depth {}: Processing content of {} characters", recursionDepth, content.length());

        // If content is small enough, create single chunk
        if (content.length() <= maxSize) {
            Chunk chunk = createChunk(title, content, startPage, endPage,
                    null, title, startChunkIndex, ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, document);
            chunks.add(chunk);
            log.debug("Content fits in single chunk: {} characters", content.length());
            return chunks;
        }

        // Find optimal split point starting from middle
        int targetSize = content.length() / 2; // true midpoint instead of hard limit
        int middlePoint = targetSize;
        
        // Ensure we don't exceed maxSize to guarantee recursion termination
        targetSize = Math.min(targetSize, maxSize);
        
        int tolerance = (int) (content.length() * 0.05); // 5% tolerance
        int minSearchPoint = Math.max(targetSize - tolerance, minSize);
        int maxSearchPoint = Math.min(targetSize + tolerance, content.length() - minSize);

        log.debug("Searching for split point around middle {} (range: {} to {})", 
                middlePoint, minSearchPoint, maxSearchPoint);

        // Search for best split point starting from middle
        int bestSplitPoint = -1;
        int bestSplitPointDistance = Integer.MAX_VALUE;

        for (int searchPoint = minSearchPoint; searchPoint <= maxSearchPoint; searchPoint += 50) { // Step by 50 chars
            if (searchPoint >= content.length()) break;

            // Check if this is a good split point (sentence boundary, paragraph break, etc.)
            if (isGoodSplitPoint(content, searchPoint)) {
                int distanceFromMiddle = Math.abs(searchPoint - middlePoint);
                if (distanceFromMiddle < bestSplitPointDistance) {
                    bestSplitPoint = searchPoint;
                    bestSplitPointDistance = distanceFromMiddle;
                }
            }
        }

        // If no good split point found, look for next sentence boundary
        if (bestSplitPoint == -1) {
            bestSplitPoint = findNextSentenceBoundary(content, middlePoint);
            log.debug("No good split point found, using sentence boundary at: {}", bestSplitPoint);
        }

        // If still no split point, use middle
        if (bestSplitPoint == -1 || bestSplitPoint <= 0 || bestSplitPoint >= content.length()) {
            bestSplitPoint = middlePoint;
            log.debug("Using middle point as split: {}", bestSplitPoint);
        }

        // Create first chunk
        String firstChunkContent = content.substring(0, bestSplitPoint).trim();
        String firstChunkTitle = titleGenerator.generateChunkTitle(title, 0, 2, false);
        
        Chunk firstChunk = createChunk(firstChunkTitle, firstChunkContent, startPage, endPage,
                null, title, startChunkIndex, ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, document);
        chunks.add(firstChunk);

        log.debug("Created first chunk: {} characters at split point {}", firstChunkContent.length(), bestSplitPoint);

        // Recursively process remaining content
        String remainingContent = content.substring(bestSplitPoint).trim();
        if (!remainingContent.isEmpty()) {
            List<Chunk> remainingChunks = splitContentRecursively(
                    remainingContent, title, startPage, endPage, request,
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
     * Check if a position is a good split point
     */
    private boolean isGoodSplitPoint(String content, int position) {
        if (position <= 0 || position >= content.length()) {
            return false;
        }

        // Check for paragraph breaks (double newline)
        if (position >= 2 && content.substring(position - 2, position).equals("\n\n")) {
            return true;
        }

        // Check for sentence endings followed by space or newline
        if (position > 0 && position < content.length() - 1) {
            char before = content.charAt(position - 1);
            char after = content.charAt(position);
            
            if ((before == '.' || before == '!' || before == '?') && 
                (after == ' ' || after == '\n' || after == '\t')) {
                return true;
            }
        }

        // Check for section headers (lines starting with numbers or capital letters)
        if (position > 0 && content.charAt(position - 1) == '\n') {
            // Look ahead for potential section header
            int endOfLine = content.indexOf('\n', position);
            if (endOfLine == -1) endOfLine = content.length();
            
            String line = content.substring(position, endOfLine).trim();
            if (line.matches("^\\d+\\..*") || line.matches("^[A-Z][A-Z\\s]+$")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Find next sentence boundary after a given position
     */
    private int findNextSentenceBoundary(String content, int startPosition) {
        if (startPosition >= content.length()) {
            return -1;
        }

        // Look for sentence endings (.!?) followed by space or newline
        for (int i = startPosition; i < content.length() - 1; i++) {
            char current = content.charAt(i);
            char next = content.charAt(i + 1);
            
            if ((current == '.' || current == '!' || current == '?') && 
                (next == ' ' || next == '\n' || next == '\t')) {
                return i + 1; // Return position after the sentence ending
            }
        }

        return -1;
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
            
            // Also combine if current chunk is below minimum size (more aggressive)
            if (!shouldCombine && currentChunk.getCharacterCount() < minSize) {
                shouldCombine = true;
            }
            
            // Very aggressive: combine if current chunk is smaller than threshold (for better quiz generation)
            int aggressiveThreshold = request.getAggressiveCombinationThreshold() != null ? 
                    request.getAggressiveCombinationThreshold() : 5000; // Raised from 3000 to 5000
            if (!shouldCombine && currentChunk.getCharacterCount() < aggressiveThreshold) {
                shouldCombine = true;
            }
            
            // Also combine if both chunks are small and combining gets closer to target size
            if (!shouldCombine && currentChunk.getCharacterCount() < minSize && nextChunk.getCharacterCount() < minSize) {
                shouldCombine = combinedSize <= 100000 && combinedSize > Math.max(currentChunk.getCharacterCount(), nextChunk.getCharacterCount());
            }

            log.debug("Chunk {}: {} chars, Chunk {}: {} chars, Combined: {} chars, Should combine: {}",
                    i - 1, currentChunk.getCharacterCount(), i, nextChunk.getCharacterCount(), combinedSize, shouldCombine);

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
                combinedChunk.setChapterTitle(currentChunk.getChapterTitle());
                combinedChunk.setSectionTitle(currentChunk.getSectionTitle());
                combinedChunk.setDocumentTitle(currentChunk.getDocumentTitle());
                combinedChunk.setDocumentAuthor(currentChunk.getDocumentAuthor());
                combinedChunk.setConverterType(currentChunk.getConverterType());
                combinedChunk.setChunkType(currentChunk.getChunkType());
                combinedChunk.setChunkIndex(currentChunk.getChunkIndex());

                log.debug("Combined chunks {} and {}: {} characters", i - 1, i, combinedContent.length());
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
     * Create a chunk with explicit strategy assignment based on logical context
     */
    private Chunk createChunk(String title, String content, Integer startPage, Integer endPage,
                              String chapterTitle, String sectionTitle, int chunkIndex,
                              ProcessDocumentRequest.ChunkingStrategy strategy, ConvertedDocument document) {
        Chunk chunk = new Chunk();
        chunk.setTitle(title.length() > 100 ? title.substring(0, 97) + "..." : title);
        chunk.setContent(content);
        chunk.setStartPage(startPage);
        chunk.setEndPage(endPage);
        chunk.setWordCount(countWords(content));
        chunk.setCharacterCount(content.length());
        chunk.setChapterTitle(chapterTitle);
        chunk.setSectionTitle(sectionTitle);
        
        // Handle null document case (when called from optimizeChapterChunks)
        if (document != null) {
            chunk.setDocumentTitle(document.getTitle());
            chunk.setDocumentAuthor(document.getAuthor());
            chunk.setConverterType(document.getConverterType());
        } else {
            // Use default values when document is null
            chunk.setDocumentTitle("Unknown Document");
            chunk.setDocumentAuthor("Unknown Author");
            chunk.setConverterType("Unknown");
        }

        // Explicitly set chunk type based on logical context
        if (strategy == ProcessDocumentRequest.ChunkingStrategy.SECTION_BASED && sectionTitle != null) {
            chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.SECTION_BASED);
        } else if (strategy == ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED && chapterTitle != null) {
            chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        } else {
            chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        }

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
        return ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED;
    }

    @Override
    public boolean canHandle(ProcessDocumentRequest.ChunkingStrategy strategy) {
        return strategy == ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED ||
                strategy == ProcessDocumentRequest.ChunkingStrategy.AUTO;
    }
} 