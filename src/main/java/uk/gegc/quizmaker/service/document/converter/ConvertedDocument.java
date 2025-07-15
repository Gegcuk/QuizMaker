package uk.gegc.quizmaker.service.document.converter;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Common output format for all document converters.
 * This standardized format makes it easier to implement chunking logic
 * that works consistently across different input formats.
 */
@Data
public class ConvertedDocument {
    
    /**
     * Document metadata
     */
    private String title;
    private String author;
    private String originalFilename;
    private String contentType;
    private Integer totalPages;
    private Long fileSize;
    
    /**
     * The full text content of the document
     */
    private String fullContent;
    
    /**
     * Structured content with chapters and sections
     */
    private List<Chapter> chapters = new ArrayList<>();
    
    /**
     * Any additional metadata extracted from the document
     */
    private String metadata;
    
    /**
     * Processing information
     */
    private String converterType;
    private String processingNotes;
    
    @Data
    public static class Chapter {
        private String title;
        private String content;
        private Integer startPage;
        private Integer endPage;
        private List<Section> sections = new ArrayList<>();
    }
    
    @Data
    public static class Section {
        private String title;
        private String content;
        private Integer startPage;
        private Integer endPage;
        private String chapterTitle;
        private Integer chapterNumber;
        private Integer sectionNumber;
    }
} 