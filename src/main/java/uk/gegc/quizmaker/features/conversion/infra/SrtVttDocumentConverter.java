package uk.gegc.quizmaker.features.conversion.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * SRT/VTT subtitle document converter.
 * Extracts text content from subtitle files, removing timestamps and formatting.
 */
@Component("documentProcessSrtVttConverter")
@Slf4j
public class SrtVttDocumentConverter implements DocumentConverter {

    // Pattern to match SRT timestamps (00:00:00,000 --> 00:00:00,000)
    private static final Pattern SRT_TIMESTAMP_PATTERN = Pattern.compile(
            "\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}"
    );
    
    // Pattern to match VTT timestamps (00:00:00.000 --> 00:00:00.000)
    private static final Pattern VTT_TIMESTAMP_PATTERN = Pattern.compile(
            "\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"
    );
    
    // Pattern to match subtitle sequence numbers
    private static final Pattern SEQUENCE_NUMBER_PATTERN = Pattern.compile("^\\d+$");

    @Override
    public boolean supports(String filenameOrMime) {
        if (filenameOrMime == null) return false;
        String lower = filenameOrMime.toLowerCase();
        return lower.endsWith(".srt") || lower.endsWith(".vtt") || 
               lower.equals("text/vtt") || lower.equals("application/x-subrip");
    }

    @Override
    public ConversionResult convert(byte[] bytes) throws ConversionException {
        try {
            String content = new String(bytes, StandardCharsets.UTF_8);
            StringBuilder extractedText = new StringBuilder();
            
            String[] lines = content.split("\\r?\\n");
            boolean inStyleBlock = false;
            
            for (String line : lines) {
                String trimmedLine = line.trim();
                
                // Skip empty lines
                if (trimmedLine.isEmpty()) {
                    continue;
                }
                
                // Skip VTT header & metadata
                if (trimmedLine.startsWith("WEBVTT")) {
                    continue;
                }
                
                // Handle STYLE blocks - start and end detection
                if (trimmedLine.startsWith("STYLE")) {
                    inStyleBlock = true;
                    continue;
                }
                if (inStyleBlock && trimmedLine.equals("}")) {
                    inStyleBlock = false;
                    continue;
                }
                if (inStyleBlock) {
                    continue; // Skip all lines within STYLE block
                }
                
                // Skip NOTE blocks
                if (trimmedLine.startsWith("NOTE")) {
                    continue;
                }
                
                // Skip sequence numbers
                if (SEQUENCE_NUMBER_PATTERN.matcher(trimmedLine).matches()) {
                    continue;
                }
                
                // Skip timestamp lines
                if (SRT_TIMESTAMP_PATTERN.matcher(trimmedLine).find() || 
                    VTT_TIMESTAMP_PATTERN.matcher(trimmedLine).find()) {
                    continue;
                }
                
                // This should be subtitle text - add it with newline for readability
                extractedText.append(trimmedLine).append("\n");
            }
            
            String text = extractedText.toString().trim();
            
            // Clean up multiple spaces
            text = text.replaceAll("\\s+", " ");
            
            return new ConversionResult(text);
            
        } catch (Exception e) {
            throw new ConversionException("Failed to convert SRT/VTT document: " + e.getMessage(), e);
        }
    }
}
