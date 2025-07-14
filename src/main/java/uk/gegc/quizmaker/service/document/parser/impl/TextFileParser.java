package uk.gegc.quizmaker.service.document.parser.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.service.document.parser.FileParser;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class TextFileParser implements FileParser {

    private static final List<String> SUPPORTED_CONTENT_TYPES = Arrays.asList(
            "text/plain",
            "text/txt"
    );

    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".txt",
            ".text"
    );

    @Override
    public boolean canParse(String contentType, String filename) {
        return SUPPORTED_CONTENT_TYPES.contains(contentType) || 
               filename.toLowerCase().endsWith(".txt") ||
               filename.toLowerCase().endsWith(".text");
    }

    @Override
    public ParsedDocument parse(InputStream inputStream, String filename) throws Exception {
        StringBuilder content = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        
        ParsedDocument parsedDocument = new ParsedDocument();
        parsedDocument.setContent(content.toString());
        
        // Extract chapters and sections from the text
        extractChaptersAndSections(parsedDocument, content.toString());
        
        return parsedDocument;
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
                currentChapterObj.setStartPage(1); // Text files don't have pages
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
                currentSectionObj.setStartPage(1);
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
            currentSectionObj.setEndPage(1);
            if (currentChapterObj != null) {
                currentChapterObj.getSections().add(currentSectionObj);
            }
        }
        
        if (currentChapterObj != null) {
            currentChapterObj.setContent(currentContent.toString());
            currentChapterObj.setEndPage(1);
            document.getChapters().add(currentChapterObj);
        }
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