package uk.gegc.quizmaker.service.document.parser.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.service.document.parser.FileParser;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class PdfFileParser implements FileParser {

    private static final List<String> SUPPORTED_CONTENT_TYPES = Arrays.asList(
            "application/pdf"
    );

    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".pdf"
    );

    @Override
    public boolean canParse(String contentType, String filename) {
        return SUPPORTED_CONTENT_TYPES.contains(contentType) || 
               filename.toLowerCase().endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(InputStream inputStream, String filename) throws Exception {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            
            ParsedDocument parsedDocument = new ParsedDocument();
            parsedDocument.setContent(text);
            parsedDocument.setTotalPages(document.getNumberOfPages());
            
            // Extract chapters and sections from the text
            extractChaptersAndSections(parsedDocument, text);
            
            return parsedDocument;
        }
    }

    private void extractChaptersAndSections(ParsedDocument document, String text) {
        // Pattern to match chapter headers (e.g., "Chapter 1", "CHAPTER 1", "1. Chapter Title")
        Pattern chapterPattern = Pattern.compile(
            "(?i)(?:chapter\\s+(\\d+)|(\\d+)\\.\\s*([^\\n]+)|CHAPTER\\s+(\\d+))",
            Pattern.MULTILINE
        );
        
        // Pattern to match section headers (e.g., "1.1", "Section 1", "1.1.1")
        Pattern sectionPattern = Pattern.compile(
            "(?i)((?:\\d+\\.)+\\d+|section\\s+\\d+|\\d+\\.\\d+\\s+[^\\n]+)",
            Pattern.MULTILINE
        );

        String[] lines = text.split("\\n");
        int currentChapter = 0;
        int currentSection = 0;
        
        StringBuilder currentContent = new StringBuilder();
        ParsedDocument.Chapter currentChapterObj = null;
        ParsedDocument.Section currentSectionObj = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // Check for chapter headers
            Matcher chapterMatcher = chapterPattern.matcher(line);
            if (chapterMatcher.find()) {
                // Save previous chapter if exists
                if (currentChapterObj != null) {
                    currentChapterObj.setContent(currentContent.toString());
                    document.getChapters().add(currentChapterObj);
                }
                
                // Start new chapter
                currentChapter++;
                currentChapterObj = new ParsedDocument.Chapter();
                currentChapterObj.setTitle(line);
                currentChapterObj.setStartPage(estimatePageNumber(i, lines.length, document.getTotalPages()));
                currentContent = new StringBuilder();
                
                // Reset section for new chapter
                currentSection = 0;
                currentSectionObj = null;
            }
            
            // Check for section headers
            Matcher sectionMatcher = sectionPattern.matcher(line);
            if (sectionMatcher.find()) {
                // Save previous section if exists
                if (currentSectionObj != null) {
                    currentSectionObj.setContent(currentContent.toString());
                    if (currentChapterObj != null) {
                        currentChapterObj.getSections().add(currentSectionObj);
                    }
                }
                
                // Start new section
                currentSection++;
                currentSectionObj = new ParsedDocument.Section();
                currentSectionObj.setTitle(line);
                currentSectionObj.setStartPage(estimatePageNumber(i, lines.length, document.getTotalPages()));
                currentSectionObj.setChapterNumber(currentChapter);
                currentSectionObj.setSectionNumber(currentSection);
                if (currentChapterObj != null) {
                    currentSectionObj.setChapterTitle(currentChapterObj.getTitle());
                }
                currentContent = new StringBuilder();
            }
            
            // Add line to current content
            if (!line.isEmpty()) {
                currentContent.append(line).append("\n");
            }
        }
        
        // Save final chapter and section
        if (currentSectionObj != null) {
            currentSectionObj.setContent(currentContent.toString());
            currentSectionObj.setEndPage(estimatePageNumber(lines.length, lines.length, document.getTotalPages()));
            if (currentChapterObj != null) {
                currentChapterObj.getSections().add(currentSectionObj);
            }
        }
        
        if (currentChapterObj != null) {
            currentChapterObj.setContent(currentContent.toString());
            currentChapterObj.setEndPage(estimatePageNumber(lines.length, lines.length, document.getTotalPages()));
            document.getChapters().add(currentChapterObj);
        }
    }

    private int estimatePageNumber(int currentLine, int totalLines, int totalPages) {
        if (totalPages == 0) return 1;
        return Math.max(1, (currentLine * totalPages) / totalLines);
    }

    @Override
    public List<String> getSupportedContentTypes() {
        return SUPPORTED_CONTENT_TYPES;
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }
} 