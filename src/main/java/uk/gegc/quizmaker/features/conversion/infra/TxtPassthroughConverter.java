package uk.gegc.quizmaker.features.conversion.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;

import java.nio.charset.StandardCharsets;

/**
 * Simple passthrough converter for plain text files.
 * Assumes UTF-8 encoding.
 */
@Component
@Slf4j
public class TxtPassthroughConverter implements DocumentConverter {

    @Override
    public boolean supports(String filenameOrMime) {
        if (filenameOrMime == null) return false;
        String lower = filenameOrMime.toLowerCase();
        return lower.endsWith(".txt") || lower.equals("text/plain");
    }

    @Override
    public ConversionResult convert(byte[] bytes) throws ConversionException {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return new ConversionResult(text);
        } catch (Exception e) {
            throw new ConversionException("Failed to convert text file", e);
        }
    }
}
