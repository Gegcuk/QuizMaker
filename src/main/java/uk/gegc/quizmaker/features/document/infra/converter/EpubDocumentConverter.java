package uk.gegc.quizmaker.features.document.infra.converter;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class EpubDocumentConverter implements DocumentConverter {

    private static final List<String> SUPPORTED_CONTENT_TYPES = Arrays.asList(
            "application/epub+zip",
            "application/epub",
            "application/x-epub"
    );

    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".epub"
    );

    @Override
    public boolean canConvert(String contentType, String filename) {
        boolean canConvert = SUPPORTED_CONTENT_TYPES.contains(contentType) ||
                filename.toLowerCase().endsWith(".epub");
        log.debug("EpubDocumentConverter.canConvert - contentType: {}, filename: {}, result: {}",
                contentType, filename, canConvert);
        return canConvert;
    }

    @Override
    public ConvertedDocument convert(InputStream inputStream, String filename, Long fileSize) throws Exception {
        Tika tika = new Tika();
        Metadata metadata = new Metadata();

        // Extract text content using Tika
        String textContent = tika.parseToString(inputStream, metadata);

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setOriginalFilename(filename);
        convertedDocument.setContentType("application/epub+zip");
        convertedDocument.setFileSize(fileSize);
        convertedDocument.setConverterType("EPUB_DOCUMENT_CONVERTER");

        // Extract metadata from Tika metadata
        extractMetadata(convertedDocument, metadata);

        // Extract content and structure from text
        extractContentAndStructure(convertedDocument, textContent);

        return convertedDocument;
    }

    private void extractMetadata(ConvertedDocument document, Metadata metadata) {
        // Extract title
        String title = metadata.get("title");
        if (title != null && !title.isEmpty()) {
            document.setTitle(title);
        }

        // Extract author
        String author = metadata.get("Author");
        if (author != null && !author.isEmpty()) {
            document.setAuthor(author);
        }

        log.info("Extracted metadata - Title: {}, Author: {}", document.getTitle(), document.getAuthor());
    }

    private void extractContentAndStructure(ConvertedDocument document, String textContent) {
        document.setFullContent(textContent);

        // Extract chapters and sections from the text content
        extractChaptersAndSections(document, textContent);
    }

    private void extractChaptersAndSections(ConvertedDocument document, String text) {
        // Pattern to match chapter headers
        Pattern chapterPattern = Pattern.compile(
                "(?i)^\\s*(chapter\\s+\\d+|\\d+\\.\\s+[A-Z][^\\n]{3,50}|CHAPTER\\s+\\d+|" +
                        "part\\s+\\d+|PART\\s+\\d+|book\\s+\\d+|BOOK\\s+\\d+)\\s*$",
                Pattern.MULTILINE
        );

        // Pattern to match section headers
        Pattern sectionPattern = Pattern.compile(
                "(?i)^\\s*(\\d+\\.\\d+\\s+[^\\n]+|section\\s+\\d+|" +
                        "Day\\s+\\d+\\s+(Homework|Assignment)|Exercises?|Assignments?|" +
                        "Practice\\s+Problems?|Review\\s+Questions?|" +
                        "(?:\\d+\\.)+\\d+\\s+[^\\n]+)\\s*$",
                Pattern.MULTILINE
        );

        String[] lines = text.split("\n");
        int currentChapter = 0;
        int currentSection = 0;

        StringBuilder chapterContent = new StringBuilder();
        StringBuilder sectionContent = new StringBuilder();
        ConvertedDocument.Chapter currentChapterObj = null;
        ConvertedDocument.Section currentSectionObj = null;

        log.info("Processing EPUB content with {} lines", lines.length);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Check for chapter headers
            Matcher chapterMatcher = chapterPattern.matcher(line);
            if (chapterMatcher.find()) {
                log.info("Found chapter header at line {}: '{}'", i, line);

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
                currentChapterObj.setTitle(line);
                currentChapterObj.setStartPage(1);
                chapterContent = new StringBuilder();

                // Reset section for new chapter
                currentSection = 0;
                currentSectionObj = null;
                sectionContent = new StringBuilder();
            }

            // Check for section headers
            Matcher sectionMatcher = sectionPattern.matcher(line);
            if (sectionMatcher.find()) {
                log.info("Found section header at line {}: '{}'", i, line);

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
                currentSectionObj.setTitle(line);
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

        log.info("Extracted {} chapters from EPUB content", document.getChapters().size());
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
        return "EPUB_DOCUMENT_CONVERTER";
    }
} 