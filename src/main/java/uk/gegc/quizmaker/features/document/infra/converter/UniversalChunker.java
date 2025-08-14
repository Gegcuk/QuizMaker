package uk.gegc.quizmaker.service.document.chunker;

import lombok.Data;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;

import java.util.List;

/**
 * Universal chunker interface that works with the standardized ConvertedDocument format.
 * <p>
 * This replaces the old ContentChunker interface and works with any document type
 * that has been converted through the document converter system.
 */
public interface UniversalChunker {

    /**
     * Chunk the converted document according to the specified strategy
     */
    List<Chunk> chunkDocument(ConvertedDocument document, ProcessDocumentRequest request);

    /**
     * Get the chunking strategy this chunker supports
     */
    ProcessDocumentRequest.ChunkingStrategy getSupportedStrategy();

    /**
     * Check if this chunker can handle the given chunking strategy
     */
    boolean canHandle(ProcessDocumentRequest.ChunkingStrategy strategy);

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
        private String documentTitle;
        private String documentAuthor;
        private String converterType;
    }
} 