package uk.gegc.quizmaker.util;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

@Component
public class XssSanitizer {

    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?i)<script[^>]*>.*?</script>");
    private static final Pattern JAVASCRIPT_PATTERN = Pattern.compile("(?i)javascript:");
    private static final Pattern ON_EVENT_PATTERN = Pattern.compile("(?i)\\s+on\\w+\\s*=");
    private static final Pattern HTML_PATTERN = Pattern.compile("<[^>]*>");

    /**
     * Sanitizes text input by removing HTML tags and potentially dangerous content
     * @param input the text to sanitize
     * @return sanitized text, or null if input is null
     */
    public String sanitize(String input) {
        if (input == null) {
            return null;
        }

        String sanitized = input;
        
        // Remove script tags
        sanitized = SCRIPT_PATTERN.matcher(sanitized).replaceAll("");
        
        // Remove javascript: protocol
        sanitized = JAVASCRIPT_PATTERN.matcher(sanitized).replaceAll("");
        
        // Remove on* event handlers
        sanitized = ON_EVENT_PATTERN.matcher(sanitized).replaceAll("");
        
        // Remove all remaining HTML tags
        sanitized = HTML_PATTERN.matcher(sanitized).replaceAll("");
        
        // Trim whitespace
        sanitized = sanitized.trim();
        
        return sanitized;
    }

    /**
     * Sanitizes text and limits length
     * @param input the text to sanitize
     * @param maxLength maximum allowed length
     * @return sanitized and truncated text
     */
    public String sanitizeAndTruncate(String input, int maxLength) {
        String sanitized = sanitize(input);
        if (sanitized != null && sanitized.length() > maxLength) {
            return sanitized.substring(0, maxLength);
        }
        return sanitized;
    }
}
