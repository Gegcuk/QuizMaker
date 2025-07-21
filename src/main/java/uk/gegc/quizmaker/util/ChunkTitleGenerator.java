package uk.gegc.quizmaker.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Utility for generating meaningful chunk titles
 * Preserves original section/chapter titles with appropriate part numbering
 */
@Component
@Slf4j
public class ChunkTitleGenerator {

    // Pattern to detect if a title already has a part number
    private static final Pattern PART_NUMBER_PATTERN = Pattern.compile(
            "\\s*\\(Part\\s+\\d+\\)\\s*$"
    );

    /**
     * Generate a meaningful title for a chunk
     *
     * @param originalTitle    The original title (chapter/section title)
     * @param chunkIndex       The chunk index within the section/chapter
     * @param totalChunks      The total number of chunks for this section/chapter
     * @param isMultipleChunks Whether this section/chapter is split into multiple chunks
     * @return A meaningful chunk title
     */
    public String generateChunkTitle(String originalTitle, int chunkIndex, int totalChunks, boolean isMultipleChunks) {
        if (originalTitle == null || originalTitle.trim().isEmpty()) {
            return generateDefaultTitle(chunkIndex, totalChunks, isMultipleChunks);
        }

        // Clean the original title
        String cleanTitle = cleanTitle(originalTitle);

        // If there's only one chunk, return the original title
        if (!isMultipleChunks) {
            return cleanTitle;
        }

        // If there are multiple chunks, add part number
        return cleanTitle + " (Part " + (chunkIndex + 1) + ")";
    }

    /**
     * Generate a default title when no meaningful title is available
     *
     * @param chunkIndex       The chunk index
     * @param totalChunks      The total number of chunks
     * @param isMultipleChunks Whether there are multiple chunks
     * @return A default title
     */
    private String generateDefaultTitle(int chunkIndex, int totalChunks, boolean isMultipleChunks) {
        if (!isMultipleChunks) {
            return "Document";
        }

        return "Document (Part " + (chunkIndex + 1) + ")";
    }

    /**
     * Clean a title by removing existing part numbers and extra whitespace
     *
     * @param title The title to clean
     * @return The cleaned title
     */
    private String cleanTitle(String title) {
        if (title == null) {
            return "";
        }

        // Remove existing part numbers
        String cleaned = PART_NUMBER_PATTERN.matcher(title).replaceAll("");

        // Trim whitespace
        cleaned = cleaned.trim();

        // Remove trailing punctuation that might interfere with part numbering
        cleaned = cleaned.replaceAll("[.!?]+$", "");

        return cleaned;
    }

    /**
     * Generate a title for a chapter chunk
     *
     * @param chapterTitle  The chapter title
     * @param chapterNumber The chapter number
     * @param chunkIndex    The chunk index within the chapter
     * @param totalChunks   The total number of chunks for this chapter
     * @return A meaningful chapter chunk title
     */
    public String generateChapterChunkTitle(String chapterTitle, Integer chapterNumber,
                                            int chunkIndex, int totalChunks) {
        String title = chapterTitle != null ? chapterTitle : "Chapter " + chapterNumber;
        boolean isMultipleChunks = totalChunks > 1;

        return generateChunkTitle(title, chunkIndex, totalChunks, isMultipleChunks);
    }

    /**
     * Generate a title for a section chunk
     *
     * @param sectionTitle  The section title
     * @param chapterTitle  The chapter title
     * @param chapterNumber The chapter number
     * @param sectionNumber The section number
     * @param chunkIndex    The chunk index within the section
     * @param totalChunks   The total number of chunks for this section
     * @return A meaningful section chunk title
     */
    public String generateSectionChunkTitle(String sectionTitle, String chapterTitle,
                                            Integer chapterNumber, Integer sectionNumber,
                                            int chunkIndex, int totalChunks) {
        String title;

        if (sectionTitle != null && !sectionTitle.trim().isEmpty()) {
            title = sectionTitle;
        } else if (chapterNumber != null && sectionNumber != null) {
            title = chapterNumber + "." + sectionNumber + " " +
                    (chapterTitle != null ? chapterTitle : "Section");
        } else {
            title = "Section " + (sectionNumber != null ? sectionNumber : (chunkIndex + 1));
        }

        boolean isMultipleChunks = totalChunks > 1;
        return generateChunkTitle(title, chunkIndex, totalChunks, isMultipleChunks);
    }

    /**
     * Generate a title for a document-level chunk (when no chapters/sections exist)
     *
     * @param documentTitle The document title
     * @param chunkIndex    The chunk index
     * @param totalChunks   The total number of chunks
     * @return A meaningful document chunk title
     */
    public String generateDocumentChunkTitle(String documentTitle, int chunkIndex, int totalChunks) {
        String title = documentTitle != null && !documentTitle.trim().isEmpty()
                ? documentTitle : "Document";
        boolean isMultipleChunks = totalChunks > 1;

        return generateChunkTitle(title, chunkIndex, totalChunks, isMultipleChunks);
    }

    /**
     * Extract a meaningful subtitle from content (first sentence or first few words)
     *
     * @param content   The content to analyze
     * @param maxLength The maximum length for the subtitle
     * @return A meaningful subtitle
     */
    public String extractSubtitle(String content, int maxLength) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }

        String trimmed = content.trim();

        // Try to find the first sentence
        int sentenceEnd = findFirstSentenceEnd(trimmed);
        if (sentenceEnd > 0 && sentenceEnd <= maxLength) {
            return trimmed.substring(0, sentenceEnd).trim();
        }

        // If no sentence found or it's too long, take the first few words
        String[] words = trimmed.split("\\s+");
        StringBuilder subtitle = new StringBuilder();

        for (String word : words) {
            if (subtitle.length() + word.length() + 1 <= maxLength) {
                if (subtitle.length() > 0) {
                    subtitle.append(" ");
                }
                subtitle.append(word);
            } else {
                break;
            }
        }

        return subtitle.toString();
    }

    /**
     * Find the end of the first sentence
     *
     * @param text The text to analyze
     * @return The position of the first sentence end, or -1 if not found
     */
    private int findFirstSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                // Check if followed by whitespace or end of text
                if (i == text.length() - 1 || Character.isWhitespace(text.charAt(i + 1))) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /**
     * Validate that a chunk title is meaningful and not too long
     *
     * @param title The title to validate
     * @return true if the title is valid, false otherwise
     */
    public boolean isValidChunkTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }

        // Check if title is too long (more than 200 characters)
        if (title.length() > 200) {
            return false;
        }

        // Check if title contains meaningful content (not just whitespace or punctuation)
        String cleaned = title.replaceAll("[\\s\\p{Punct}]+", "");
        return cleaned.length() > 0;
    }

    /**
     * Generate a summary title for a collection of chunks
     *
     * @param originalTitle The original title
     * @param totalChunks   The total number of chunks
     * @return A summary title
     */
    public String generateSummaryTitle(String originalTitle, int totalChunks) {
        if (originalTitle == null || originalTitle.trim().isEmpty()) {
            return "Document (" + totalChunks + " parts)";
        }

        String cleanTitle = cleanTitle(originalTitle);
        return cleanTitle + " (" + totalChunks + " parts)";
    }
} 