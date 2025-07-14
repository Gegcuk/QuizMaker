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

        return chunks;
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

        // Split content into chunks of specified size
        int maxSize = request.getMaxChunkSize();
        int contentLength = content.length();
        boolean isMultipleChunks = contentLength > maxSize;
        
        for (int i = 0; i < contentLength; i += maxSize) {
            int endIndex = Math.min(i + maxSize, contentLength);
            String chunkContent = content.substring(i, endIndex);
            
            // Try to break at sentence boundaries using enhanced detection
            if (endIndex < contentLength) {
                int bestSplitPoint = sentenceBoundaryDetector.findBestSplitPoint(chunkContent, maxSize);
                if (bestSplitPoint > 0 && bestSplitPoint < chunkContent.length()) {
                    endIndex = i + bestSplitPoint;
                    chunkContent = content.substring(i, endIndex);
                }
            }
            
            // Generate meaningful title using the title generator
            String chunkTitle = titleGenerator.generateChunkTitle(title, 
                    chunkIndex - startChunkIndex, 
                    (int) Math.ceil((double) contentLength / maxSize), 
                    isMultipleChunks);
            
            Chunk chunk = createChunk(chunkTitle, chunkContent, startPage, endPage,
                    null, null, null, null, 
                    ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunkIndex);
            chunks.add(chunk);
            chunkIndex++;
        }

        return chunks;
    }



    private Chunk createChunk(String title, String content, Integer startPage, Integer endPage,
                             String chapterTitle, String sectionTitle,
                             Integer chapterNumber, Integer sectionNumber,
                             ProcessDocumentRequest.ChunkingStrategy chunkType, int chunkIndex) {
        Chunk chunk = new Chunk();
        chunk.setTitle(title);
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