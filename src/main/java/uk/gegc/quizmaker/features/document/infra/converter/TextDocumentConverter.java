package uk.gegc.quizmaker.features.document.infra.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;

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
public class TextDocumentConverter implements DocumentConverter {

    private static final List<String> SUPPORTED_CONTENT_TYPES = Arrays.asList(
            "text/plain",
            "text/txt"
    );

    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".txt",
            ".text"
    );

    @Override
    public boolean canConvert(String contentType, String filename) {
        return SUPPORTED_CONTENT_TYPES.contains(contentType) ||
                filename.toLowerCase().endsWith(".txt") ||
                filename.toLowerCase().endsWith(".text");
    }

    @Override
    public ConvertedDocument convert(InputStream inputStream, String filename, Long fileSize) throws Exception {
        // Read the text content
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }

        String text = content.toString();

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent(text);
        convertedDocument.setOriginalFilename(filename);
        convertedDocument.setContentType("text/plain");
        convertedDocument.setFileSize(fileSize);
        convertedDocument.setConverterType("TEXT_DOCUMENT_CONVERTER");

        // Extract title and author from first few lines if possible
        extractMetadata(convertedDocument, text);

        // Extract chapters and sections from the text
        extractChaptersAndSections(convertedDocument, text);

        return convertedDocument;
    }

    private void extractMetadata(ConvertedDocument document, String text) {
        String[] lines = text.split("\n");

        // Try to extract title from first non-empty line
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() > 3 && trimmed.length() < 200) {
                // Check if it looks like a title (not too long, not too short, no special patterns)
                if (!trimmed.matches(".*\\d{4}.*") && // Not a date
                        !trimmed.matches(".*\\d{1,2}:\\d{2}.*") && // Not a time
                        !trimmed.matches("^\\s*\\d+\\s*$") && // Not just a number
                        !trimmed.matches(".*[A-Z]{3,}.*")) { // Not all caps (likely headers)
                    document.setTitle(trimmed);
                    break;
                }
            }
        }

        // Try to extract author from lines containing "by" or "author"
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().contains("by ") ||
                    trimmed.toLowerCase().contains("author:") ||
                    trimmed.toLowerCase().contains("author ")) {
                String author = trimmed.replaceAll("(?i)(by|author:?)\\s*", "").trim();
                if (author.length() > 2 && author.length() < 100) {
                    document.setAuthor(author);
                    break;
                }
            }
        }
    }

    private void extractChaptersAndSections(ConvertedDocument document, String text) {
        // Pattern to match chapter headers - capture only the chapter identifier and immediate title
        // Limited to reasonable length (up to first 200 chars or first newline/period)
        Pattern chapterPattern = Pattern.compile(
                "(?i)^\\s*((?:chapter|CHAPTER)\\s+\\d+[^.\\n]{0,200}(?:\\.|\\n|$)|" +
                        "(?:part|PART)\\s+\\d+[^.\\n]{0,200}(?:\\.|\\n|$)|" +
                        "(?:book|BOOK)\\s+\\d+[^.\\n]{0,200}(?:\\.|\\n|$))",
                Pattern.MULTILINE
        );

        // Pattern to match section headers
        Pattern sectionPattern = Pattern.compile(
                "(?i)^\\s*(\\d+\\.\\d+\\s+[^\\n]{0,200}|section\\s+\\d+[^\\n]{0,200}|" +
                        "Day\\s+\\d+\\s+(Homework|Assignment)|Exercises?|Assignments?|" +
                        "Practice\\s+Problems?|Review\\s+Questions?|" +
                        "(?:\\d+\\.)+\\d+\\s+[^\\n]{0,200})\\s*$",
                Pattern.MULTILINE
        );

        String[] lines = text.split("\n");
        int currentChapter = 0;
        int currentSection = 0;

        StringBuilder chapterContent = new StringBuilder();
        StringBuilder sectionContent = new StringBuilder();
        ConvertedDocument.Chapter currentChapterObj = null;
        ConvertedDocument.Section currentSectionObj = null;

        log.info("Converting text document with {} lines", lines.length);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Check for chapter headers
            Matcher chapterMatcher = chapterPattern.matcher(line);
            if (chapterMatcher.find()) {
                // Extract only the matched chapter header (not the entire line)
                String chapterHeader = chapterMatcher.group(1).trim();
                // Further truncate if still too long
                if (chapterHeader.length() > 200) {
                    chapterHeader = chapterHeader.substring(0, 197) + "...";
                }
                log.info("Found chapter header at line {}: '{}'", i, chapterHeader);

                // Save previous chapter if exists
                if (currentChapterObj != null) {
                    // Add any remaining section content to chapter
                    if (currentSectionObj != null) {
                        currentSectionObj.setContent(sectionContent.toString());
                        currentChapterObj.getSections().add(currentSectionObj);
                        sectionContent = new StringBuilder();
                    }
                    currentChapterObj.setContent(chapterContent.toString());
                    log.info("Saving chapter '{}' with {} characters", currentChapterObj.getTitle(), chapterContent.length());
                    document.getChapters().add(currentChapterObj);
                }

                // Start new chapter
                currentChapter++;
                currentChapterObj = new ConvertedDocument.Chapter();
                currentChapterObj.setTitle(chapterHeader);
                currentChapterObj.setStartPage(1); // Text files don't have pages, use line numbers
                chapterContent = new StringBuilder();

                // Reset section for new chapter
                currentSection = 0;
                currentSectionObj = null;
                sectionContent = new StringBuilder();
            }

            // Check for section headers
            Matcher sectionMatcher = sectionPattern.matcher(line);
            if (sectionMatcher.find()) {
                // Extract only the matched section header (not the entire line)
                String sectionHeader = sectionMatcher.group(1).trim();
                // Further truncate if still too long
                if (sectionHeader.length() > 200) {
                    sectionHeader = sectionHeader.substring(0, 197) + "...";
                }
                log.info("Found section header at line {}: '{}'", i, sectionHeader);

                // Save previous section if exists
                if (currentSectionObj != null) {
                    currentSectionObj.setContent(sectionContent.toString());
                    if (currentChapterObj != null) {
                        currentChapterObj.getSections().add(currentSectionObj);
                    }
                }

                // Start new section
                currentSection++;
                currentSectionObj = new ConvertedDocument.Section();
                currentSectionObj.setTitle(sectionHeader);
                currentSectionObj.setStartPage(1);
                currentSectionObj.setChapterNumber(currentChapter);
                currentSectionObj.setSectionNumber(currentSection);
                if (currentChapterObj != null) {
                    currentSectionObj.setChapterTitle(currentChapterObj.getTitle());
                }
                sectionContent = new StringBuilder();
            }

            // Add line to current content (both chapter and section)
            if (!line.isEmpty()) {
                chapterContent.append(line).append("\n");
                sectionContent.append(line).append("\n");
            }
        }

        // Save final chapter and section
        if (currentSectionObj != null) {
            currentSectionObj.setContent(sectionContent.toString());
            currentSectionObj.setEndPage(1);
            if (currentChapterObj != null) {
                currentChapterObj.getSections().add(currentSectionObj);
            }
        }

        if (currentChapterObj != null) {
            currentChapterObj.setContent(chapterContent.toString());
            currentChapterObj.setEndPage(1);
            log.info("Saving final chapter '{}' with {} characters", currentChapterObj.getTitle(), chapterContent.length());
            document.getChapters().add(currentChapterObj);
        }

        log.info("Extracted {} chapters from text document", document.getChapters().size());
        for (ConvertedDocument.Chapter chapter : document.getChapters()) {
            log.info("Chapter '{}': {} characters, {} sections",
                    chapter.getTitle(), chapter.getContent().length(), chapter.getSections().size());
            for (ConvertedDocument.Section section : chapter.getSections()) {
                log.info("  Section '{}': {} characters", section.getTitle(), section.getContent().length());
            }
        }

        // If no chapters were detected, create a single chapter with all content
        if (document.getChapters().isEmpty()) {
            log.info("No chapters detected, creating single chapter with all content");
            ConvertedDocument.Chapter singleChapter = new ConvertedDocument.Chapter();
            singleChapter.setTitle("Document");
            singleChapter.setContent(text);
            singleChapter.setStartPage(1);
            singleChapter.setEndPage(1);
            document.getChapters().add(singleChapter);
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

    @Override
    public String getConverterType() {
        return "TEXT_DOCUMENT_CONVERTER";
    }
} 