package uk.gegc.quizmaker.service.document.chunker.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.chunker.ContentChunker;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;
import uk.gegc.quizmaker.util.ChunkTitleGenerator;
import uk.gegc.quizmaker.util.SentenceBoundaryDetector;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChapterBasedChunker implements ContentChunker {

    private final SentenceBoundaryDetector sentenceBoundaryDetector;
    private final ChunkTitleGenerator titleGenerator;

    @Override
    public List<Chunk> chunkDocument(ParsedDocument document, ProcessDocumentRequest request) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        // If document has chapters, use them
        if (!document.getChapters().isEmpty()) {
            for (ParsedDocument.Chapter chapter : document.getChapters()) {
                List<Chunk> chapterChunks = splitChapterIfNeeded(chapter, request, chunkIndex);
                chunks.addAll(chapterChunks);
                chunkIndex += chapterChunks.size();
            }
        } else {
            // If no chapters found, split the entire content by size
            chunks.addAll(splitContentBySize(document.getContent(), "Document", 1, 
                    document.getTotalPages() != null ? document.getTotalPages() : 1, 
                    request, chunkIndex));
        }

        // Post-process: combine small chunks (less than 5000 characters)
        return combineSmallChunks(chunks, 5000);
    }

    private List<Chunk> splitChapterIfNeeded(ParsedDocument.Chapter chapter, 
                                            ProcessDocumentRequest request, 
                                            int startChunkIndex) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = startChunkIndex;

        // If chapter has sections, use them
        if (!chapter.getSections().isEmpty()) {
            for (ParsedDocument.Section section : chapter.getSections()) {
                List<Chunk> sectionChunks = splitSectionIfNeeded(section, request, chunkIndex);
                chunks.addAll(sectionChunks);
                chunkIndex += sectionChunks.size();
            }
        } else {
            // If no sections, split chapter by size
            chunks.addAll(splitContentBySize(chapter.getContent(), chapter.getTitle(), 
                    chapter.getStartPage(), chapter.getEndPage(), request, chunkIndex));
        }

        return chunks;
    }

    private List<Chunk> splitSectionIfNeeded(ParsedDocument.Section section, 
                                            ProcessDocumentRequest request, 
                                            int startChunkIndex) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = startChunkIndex;

        // Check if section needs to be split
        if (section.getContent().length() <= request.getMaxChunkSize()) {
            // Section is small enough, keep as single chunk
            Chunk chunk = createChunk(section.getTitle(), section.getContent(), 
                    section.getStartPage(), section.getEndPage(), 
                    section.getChapterTitle(), section.getTitle(),
                    section.getChapterNumber(), section.getSectionNumber(),
                    ProcessDocumentRequest.ChunkingStrategy.SECTION_BASED, chunkIndex);
            chunks.add(chunk);
        } else {
            // Split section by size with meaningful titles
            chunks.addAll(splitContentBySize(section.getContent(), section.getTitle(), 
                    section.getStartPage(), section.getEndPage(), request, chunkIndex));
        }

        return chunks;
    }

    private List<Chunk> splitContentBySize(String content, String title, 
                                          Integer startPage, Integer endPage,
                                          ProcessDocumentRequest request, 
                                          int startChunkIndex) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = startChunkIndex;
        int maxSize = request.getMaxChunkSize();
        int contentLength = content.length();

        log.info("Splitting content: title='{}', totalLength={}, maxSize={}", title, contentLength, maxSize);

        // If content is smaller than max size, keep it as one chunk
        if (contentLength <= maxSize) {
            String chunkTitle = titleGenerator.generateChunkTitle(title, 0, 1, false);
            Chunk chunk = createChunk(chunkTitle, content, startPage, endPage,
                    null, null, null, null, 
                    ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, chunkIndex);
            chunks.add(chunk);
            log.info("Content fits in single chunk: {} characters", contentLength);
            return chunks;
        }

        // Split content into chunks, targeting maxSize with good boundaries
        int currentPos = 0;
        while (currentPos < contentLength) {
            int remainingLength = contentLength - currentPos;
            
            log.debug("Current position: {}, remaining length: {}, maxSize: {}", currentPos, remainingLength, maxSize);
            
            if (remainingLength <= maxSize) {
                // Last chunk - take all remaining content
                String chunkContent = content.substring(currentPos);
                String chunkTitle = titleGenerator.generateChunkTitle(title, chunkIndex - startChunkIndex, 
                        (int) Math.ceil((double) contentLength / maxSize), true);
                Chunk chunk = createChunk(chunkTitle, chunkContent, startPage, endPage,
                        null, null, null, null, 
                        ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunkIndex);
                chunks.add(chunk);
                log.info("Final chunk: {} characters", chunkContent.length());
                break;
            }

            // Target maxSize exactly, then look for good boundaries
            int targetSplitPoint = currentPos + maxSize;
            
            // Look for good boundaries in a wide range around maxSize
            // Search from 70% to 110% of maxSize to find the best boundary
            int searchStart = currentPos + (int)(maxSize * 0.7);
            int searchEnd = Math.min(currentPos + (int)(maxSize * 1.1), contentLength);
            
            log.debug("Target split point: {}, search range: {} to {}", targetSplitPoint, searchStart, searchEnd);
            
            int bestSplitPoint = findBestSplitPoint(content, searchStart, searchEnd, targetSplitPoint);
            
            // Ensure we're making progress
            if (bestSplitPoint <= currentPos) {
                log.warn("No progress made in chunking, forcing advance by maxSize");
                bestSplitPoint = Math.min(currentPos + maxSize, contentLength);
            }
            
            // Create chunk
            String chunkContent = content.substring(currentPos, bestSplitPoint);
            String chunkTitle = titleGenerator.generateChunkTitle(title, chunkIndex - startChunkIndex, 
                    (int) Math.ceil((double) contentLength / maxSize), true);
            Chunk chunk = createChunk(chunkTitle, chunkContent, startPage, endPage,
                        null, null, null, null, 
                        ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunkIndex);
            chunks.add(chunk);
            
            log.info("Created chunk {}: {} characters (target was {})", 
                    chunkIndex - startChunkIndex + 1, chunkContent.length(), maxSize);
            
            currentPos = bestSplitPoint;
            chunkIndex++;
        }

        log.info("Total chunks created: {}", chunks.size());
        return chunks;
    }

    /**
     * Find the best split point within a given range, preferring points close to target
     */
    private int findBestSplitPoint(String content, int searchStart, int searchEnd, int targetSplitPoint) {
        // Ensure we don't exceed content length
        if (targetSplitPoint >= content.length()) {
            return content.length();
        }
        
        // Extract the portion of content we want to analyze (from current position to search end)
        String contentToAnalyze = content.substring(0, searchEnd);
        
        // Calculate the relative target position within the content to analyze
        // Ensure it doesn't exceed the content to analyze length
        int relativeTarget = Math.min(targetSplitPoint, contentToAnalyze.length());
        
        log.debug("Content to analyze length: {}, relative target: {}", contentToAnalyze.length(), relativeTarget);
        
        // Use the injected SentenceBoundaryDetector with the content to analyze
        // The detector will look for sentence boundaries within the target range
        int splitPoint = sentenceBoundaryDetector.findBestSplitPoint(contentToAnalyze, relativeTarget);
        
        log.debug("Detector returned split point: {}", splitPoint);
        
        // Ensure the split point is within our search range
        if (splitPoint < searchStart) {
            splitPoint = searchStart;
        }
        if (splitPoint > searchEnd) {
            splitPoint = searchEnd;
        }
        
        log.debug("Final split point: {} (range: {} to {})", splitPoint, searchStart, searchEnd);
        
        return splitPoint;
    }



    /**
     * Combine small chunks with the next chunk
     */
    private List<Chunk> combineSmallChunks(List<Chunk> chunks, int minSize) {
        if (chunks.isEmpty()) return chunks;
        
        log.info("Combining chunks: {} chunks, minSize={}", chunks.size(), minSize);
        
        List<Chunk> result = new ArrayList<>();
        Chunk currentChunk = chunks.get(0);
        
        for (int i = 1; i < chunks.size(); i++) {
            Chunk nextChunk = chunks.get(i);
            
            // If current chunk is small and combining won't exceed max size, combine them
            // Also combine if the combined size would be closer to maxSize than separate chunks
            int combinedSize = currentChunk.getCharacterCount() + nextChunk.getCharacterCount();
            boolean shouldCombine = currentChunk.getCharacterCount() < minSize && combinedSize <= 100000;
            
            // Also combine if both chunks are small and combining gets closer to target size
            if (!shouldCombine && currentChunk.getCharacterCount() < 20000 && nextChunk.getCharacterCount() < 20000) {
                shouldCombine = combinedSize <= 100000 && combinedSize > Math.max(currentChunk.getCharacterCount(), nextChunk.getCharacterCount());
            }
            
            log.debug("Chunk {}: {} chars, Chunk {}: {} chars, Combined: {} chars, Should combine: {}", 
                    i-1, currentChunk.getCharacterCount(), i, nextChunk.getCharacterCount(), combinedSize, shouldCombine);
            
            if (shouldCombine) {
                
                // Combine chunks
                String combinedContent = currentChunk.getContent() + "\n\n" + nextChunk.getContent();
                
                // Create a more concise combined title
                String combinedTitle = currentChunk.getTitle() + " + " + nextChunk.getTitle();
                
                Chunk combinedChunk = new Chunk();
                combinedChunk.setTitle(combinedTitle.length() > 100 ? combinedTitle.substring(0, 97) + "..." : combinedTitle);
                combinedChunk.setContent(combinedContent);
                combinedChunk.setStartPage(currentChunk.getStartPage());
                combinedChunk.setEndPage(nextChunk.getEndPage());
                combinedChunk.setWordCount(currentChunk.getWordCount() + nextChunk.getWordCount());
                combinedChunk.setCharacterCount(combinedContent.length());
                combinedChunk.setChapterTitle(currentChunk.getChapterTitle());
                combinedChunk.setSectionTitle(currentChunk.getSectionTitle());
                combinedChunk.setChapterNumber(currentChunk.getChapterNumber());
                combinedChunk.setSectionNumber(currentChunk.getSectionNumber());
                combinedChunk.setChunkType(currentChunk.getChunkType());
                combinedChunk.setChunkIndex(currentChunk.getChunkIndex());
                
                currentChunk = combinedChunk;
            } else {
                // Keep current chunk as is and move to next
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
        
        return result;
    }



    private Chunk createChunk(String title, String content, Integer startPage, Integer endPage,
                             String chapterTitle, String sectionTitle,
                             Integer chapterNumber, Integer sectionNumber,
                             ProcessDocumentRequest.ChunkingStrategy chunkType, int chunkIndex) {
        Chunk chunk = new Chunk();
        chunk.setTitle(title.length() > 100 ? title.substring(0, 97) + "..." : title);
        chunk.setContent(content);
        chunk.setStartPage(startPage);
        chunk.setEndPage(endPage);
        chunk.setWordCount(countWords(content));
        chunk.setCharacterCount(content.length());
        chunk.setChapterTitle(chapterTitle);
        chunk.setSectionTitle(sectionTitle);
        chunk.setChapterNumber(chapterNumber);
        chunk.setSectionNumber(sectionNumber);
        chunk.setChunkType(chunkType);
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
} 