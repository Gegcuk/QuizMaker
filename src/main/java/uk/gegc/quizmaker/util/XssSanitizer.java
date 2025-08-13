package uk.gegc.quizmaker.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

@Component
public class XssSanitizer {

    public String sanitize(String input) {
        if (input == null) {
            return null;
        }

        // Plain-text policy: strip all HTML and potentially dangerous content
        String cleaned = Jsoup.clean(input, Safelist.none());
        cleaned = stripControlChars(cleaned).trim();
        return cleaned;
    }

    public String sanitizeAndTruncate(String input, int maxCodePoints) {
        String sanitized = sanitize(input);
        if (sanitized == null) {
            return null;
        }
        String normalized = Normalizer.normalize(sanitized, Normalizer.Form.NFKC).trim();
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end);
    }

    private String stripControlChars(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints()
                .filter(c -> !Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t')
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }
}
