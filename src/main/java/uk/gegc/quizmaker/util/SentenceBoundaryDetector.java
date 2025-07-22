package uk.gegc.quizmaker.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced sentence boundary detection utility
 * Handles various sentence endings and edge cases for better content chunking
 */
@Component
@Slf4j
public class SentenceBoundaryDetector {

    // Pattern for sentence endings: . ! ? followed by whitespace or end of text
    // Also handles common abbreviations and edge cases
    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile(
            "([.!?])\\s+"
    );

    // Pattern for common abbreviations that shouldn't end sentences
    private static final Pattern ABBREVIATION_PATTERN = Pattern.compile(
            "\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|Inc|Ltd|Corp|Co|vs|etc|i\\.e|e\\.g|a\\.m|p\\.m|U\\.S|U\\.K|Ph\\.D|M\\.A|B\\.A|etc\\.)\\."
    );

    // Pattern for numbers with decimals
    private static final Pattern DECIMAL_PATTERN = Pattern.compile(
            "\\d+\\.\\d+"
    );

    // Pattern for ellipsis
    private static final Pattern ELLIPSIS_PATTERN = Pattern.compile(
            "\\.{3,}"
    );

    /**
     * Find the last sentence boundary in the given text
     *
     * @param text The text to analyze
     * @return The index of the last sentence boundary, or -1 if not found
     */
    public int findLastSentenceEnd(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        // Start from the end and work backwards
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);

            if (isSentenceEnding(c, text, i)) {
                return i + 1; // Return position after the sentence ending
            }
        }

        return -1;
    }

    /**
     * Check if a character at the given position is a sentence ending
     *
     * @param c        The character to check
     * @param text     The full text
     * @param position The position of the character
     * @return true if it's a sentence ending, false otherwise
     */
    private boolean isSentenceEnding(char c, String text, int position) {
        if (c != '.' && c != '!' && c != '?') {
            return false;
        }

        // Check if followed by whitespace or end of text
        if (position < text.length() - 1) {
            char nextChar = text.charAt(position + 1);
            if (!Character.isWhitespace(nextChar)) {
                return false;
            }
        }

        // Handle special cases
        if (c == '.') {
            return !isAbbreviation(text, position) &&
                    !isDecimal(text, position) &&
                    !isEllipsis(text, position);
        }

        return true;
    }

    /**
     * Check if the period is part of an abbreviation
     *
     * @param text     The full text
     * @param position The position of the period
     * @return true if it's an abbreviation, false otherwise
     */
    private boolean isAbbreviation(String text, int position) {
        // Look for common abbreviations before the period
        int start = Math.max(0, position - 20); // Look back up to 20 characters
        String beforePeriod = text.substring(start, position + 1);

        return ABBREVIATION_PATTERN.matcher(beforePeriod).find();
    }

    /**
     * Check if the period is part of a decimal number
     *
     * @param text     The full text
     * @param position The position of the period
     * @return true if it's a decimal, false otherwise
     */
    private boolean isDecimal(String text, int position) {
        // Look for digits before and after the period
        int start = Math.max(0, position - 10);
        int end = Math.min(text.length(), position + 10);
        String aroundPeriod = text.substring(start, end);

        return DECIMAL_PATTERN.matcher(aroundPeriod).find();
    }

    /**
     * Check if the period is part of an ellipsis
     *
     * @param text     The full text
     * @param position The position of the period
     * @return true if it's an ellipsis, false otherwise
     */
    private boolean isEllipsis(String text, int position) {
        // Look for multiple periods around this position
        int start = Math.max(0, position - 2);
        int end = Math.min(text.length(), position + 3);
        String aroundPeriod = text.substring(start, end);

        return ELLIPSIS_PATTERN.matcher(aroundPeriod).find();
    }

    /**
     * Find the best split point in text that respects sentence boundaries
     *
     * @param text      The text to split
     * @param maxLength The maximum length for the chunk
     * @return The best split point, or maxLength if no good boundary found
     */
    public int findBestSplitPoint(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text != null ? text.length() : 0;
        }

        // Try to find a sentence boundary within the last 20% of the max length (more flexible)
        int searchStart = Math.max(0, maxLength - (maxLength / 5));
        String searchText = text.substring(0, maxLength);

        // First, try to find natural breaks like list items or exercise patterns
        int naturalBreak = findNaturalBreak(searchText, searchStart);
        if (naturalBreak > searchStart) {
            return naturalBreak;
        }

        // Then try sentence boundaries
        int sentenceEnd = findLastSentenceEnd(searchText);

        if (sentenceEnd > searchStart) {
            return sentenceEnd;
        }

        // If no good sentence boundary found, try to break at word boundaries
        return findWordBoundary(text, maxLength);
    }

    /**
     * Find natural breaks like list items, exercise patterns, or paragraph breaks
     *
     * @param text        The text to analyze
     * @param searchStart The minimum position to search from
     * @return The position of the natural break, or -1 if not found
     */
    private int findNaturalBreak(String text, int searchStart) {
        if (searchStart >= text.length()) {
            return -1;
        }

        // Look for patterns that indicate natural breaks
        String searchText = text.substring(searchStart);

        // Explicitly match numbered list starts (e.g., "1. Some text", "a. Some text")
        // This is more specific than the previous pattern
        Pattern numberedListPattern = Pattern.compile("\\n\\s*\\d+\\.\\s+[A-Z]");
        Matcher numberedMatcher = numberedListPattern.matcher(searchText);
        if (numberedMatcher.find()) {
            return searchStart + numberedMatcher.start();
        }

        // Pattern for bullet points (e.g., "•", "-", "*")
        Pattern bulletPattern = Pattern.compile("\\n\\s*[•\\-*]\\s+");
        Matcher bulletMatcher = bulletPattern.matcher(searchText);
        if (bulletMatcher.find()) {
            return searchStart + bulletMatcher.start();
        }

        // Pattern for exercise instructions (e.g., "Find", "Do", "Complete")
        Pattern exercisePattern = Pattern.compile("\\n\\s*(?:Find|Do|Complete|Practice|Review)\\s+", Pattern.CASE_INSENSITIVE);
        Matcher exerciseMatcher = exercisePattern.matcher(searchText);
        if (exerciseMatcher.find()) {
            return searchStart + exerciseMatcher.start();
        }

        // Pattern for paragraph breaks (double newlines)
        Pattern paragraphPattern = Pattern.compile("\\n\\s*\\n");
        Matcher paragraphMatcher = paragraphPattern.matcher(searchText);
        if (paragraphMatcher.find()) {
            return searchStart + paragraphMatcher.end();
        }

        return -1;
    }

    /**
     * Find a word boundary near the target position
     *
     * @param text           The text to analyze
     * @param targetPosition The target position
     * @return The position of the word boundary
     */
    private int findWordBoundary(String text, int targetPosition) {
        if (targetPosition >= text.length()) {
            return text.length();
        }

        // Look backwards from target position for a word boundary
        for (int i = targetPosition; i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }

        // If no word boundary found, return the target position
        return targetPosition;
    }

    /**
     * Validate that a chunk doesn't break in the middle of a sentence
     *
     * @param chunkContent The chunk content to validate
     * @return true if the chunk is valid, false otherwise
     */
    public boolean isValidChunk(String chunkContent) {
        if (chunkContent == null || chunkContent.isEmpty()) {
            return true;
        }

        // Check if the chunk ends with an incomplete sentence
        String trimmed = chunkContent.trim();
        if (trimmed.isEmpty()) {
            return true;
        }

        // If it ends with a sentence ending, it's valid
        if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
            return true;
        }

        // If it ends with a word boundary (whitespace), it's likely valid
        if (Character.isWhitespace(trimmed.charAt(trimmed.length() - 1))) {
            return true;
        }

        // Check if it ends with common incomplete sentence patterns
        String lastWord = getLastWord(trimmed);
        if (lastWord != null && isIncompleteSentenceIndicator(lastWord)) {
            return false;
        }

        return true;
    }

    /**
     * Get the last word from the text
     *
     * @param text The text to analyze
     * @return The last word, or null if not found
     */
    private String getLastWord(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String[] words = text.trim().split("\\s+");
        return words.length > 0 ? words[words.length - 1] : null;
    }

    /**
     * Check if a word indicates an incomplete sentence
     *
     * @param word The word to check
     * @return true if it indicates an incomplete sentence, false otherwise
     */
    private boolean isIncompleteSentenceIndicator(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }

        // Common words that often indicate incomplete sentences
        String[] incompleteIndicators = {
                "the", "a", "an", "and", "or", "but", "if", "when", "while", "because",
                "although", "however", "therefore", "thus", "hence", "consequently"
        };

        String lowerWord = word.toLowerCase();
        for (String indicator : incompleteIndicators) {
            if (lowerWord.equals(indicator)) {
                return true;
            }
        }

        return false;
    }
} 