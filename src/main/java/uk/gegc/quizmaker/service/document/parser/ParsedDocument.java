package uk.gegc.quizmaker.service.document.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ParsedDocument {
    private String title;
    private String author;
    private String content;
    private List<Chapter> chapters = new ArrayList<>();
    private List<Section> sections = new ArrayList<>();
    private Integer totalPages;
    private String metadata;

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