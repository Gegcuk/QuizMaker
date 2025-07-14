package uk.gegc.quizmaker.service.document.chunker;

import lombok.Data;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;

import java.util.List;

public interface ContentChunker {

    /**
     * Chunk the parsed document according to the specified strategy
     */
    List<Chunk> chunkDocument(ParsedDocument document, ProcessDocumentRequest request);

    /**
     * Get the chunking strategy this chunker supports
     */
    ProcessDocumentRequest.ChunkingStrategy getSupportedStrategy();

    @Data
    class Chunk {
        private String title;
        private String content;
        private Integer startPage;
        private Integer endPage;
        private Integer wordCount;
        private Integer characterCount;
        private String chapterTitle;
        private String sectionTitle;
        private Integer chapterNumber;
        private Integer sectionNumber;
        private ProcessDocumentRequest.ChunkingStrategy chunkType;
        private Integer chunkIndex;
    }
} 