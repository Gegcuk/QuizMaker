package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Service for normalizing text to create stable character offsets.
 * This is a pure function service with no side effects.
 */
@Service("documentProcessNormalizationService")
@Slf4j
public class NormalizationService {

    @Value("${docproc.normalization.dehyphenate:true}")
    private boolean dehyphenate;

    @Value("${docproc.normalization.collapse-spaces:true}")
    private boolean collapseSpaces;

    // Pattern for line endings: \r\n|\r→\n, plus Unicode line/paragraph separators
    private static final Pattern LINE_ENDING_PATTERN = Pattern.compile("\\r\\n?|\\u2028|\\u2029");
    
    // Pattern for multiple spaces (2 or more)
    private static final Pattern MULTIPLE_SPACES_PATTERN = Pattern.compile(" {2,}");
    
    // Pattern for hyphenation across line breaks (requires letters on both sides)
    private static final Pattern HYPHENATION_PATTERN = Pattern.compile("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{L})");
    
    // Pattern for zero-width characters and BOM
    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    
    // Pattern for smart double quotes
    private static final Pattern SMART_DOUBLE_QUOTES_PATTERN = Pattern.compile("[\u201C\u201D\u201E]");
    
    // Pattern for smart single quotes
    private static final Pattern SMART_SINGLE_QUOTES_PATTERN = Pattern.compile("[\u2018\u2019\u201A]");
    
    // Pattern for smart dashes
    private static final Pattern SMART_DASHES_PATTERN = Pattern.compile("[\u2013\u2014]");

    /**
     * Normalizes text according to configured rules.
     * 
     * @param text the text to normalize
     * @return NormalizationResult containing normalized text and character count
     */
    public NormalizationResult normalize(String text) {
        if (text == null) {
            return new NormalizationResult("", 0);
        }

        String normalized = text;

        // 1. Normalize line endings: \r\n|\r → \n, plus Unicode separators
        normalized = LINE_ENDING_PATTERN.matcher(normalized).replaceAll("\n");

        // 2. Safe de-hyphenate across line breaks (if enabled)
        if (dehyphenate) {
            normalized = HYPHENATION_PATTERN.matcher(normalized).replaceAll("");
        }

        // 3. Collapse multiple spaces to single space (if enabled)
        if (collapseSpaces) {
            normalized = MULTIPLE_SPACES_PATTERN.matcher(normalized).replaceAll(" ");
        }

        // 4. NFC unicode normalization
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);

        // 5. Remove zero-width characters and BOM
        normalized = ZERO_WIDTH_PATTERN.matcher(normalized).replaceAll("");

        // 6. Normalize smart quotes (separate singles and doubles)
        normalized = SMART_DOUBLE_QUOTES_PATTERN.matcher(normalized).replaceAll("\"");
        normalized = SMART_SINGLE_QUOTES_PATTERN.matcher(normalized).replaceAll("'");

        // 7. Normalize smart dashes
        normalized = SMART_DASHES_PATTERN.matcher(normalized).replaceAll("-");

        // Note: String.length() counts UTF-16 code units, which is consistent for Java string operations
        // and will be used for offset calculations in AI structure building
        int charCount = normalized.length();

        return new NormalizationResult(normalized, charCount);
    }
}
