package uk.gegc.quizmaker.features.conversion.infra;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;

import java.nio.charset.StandardCharsets;

/**
 * HTML document converter using JSoup.
 * Extracts plain text from HTML documents with safety cleaning.
 */
@Component("documentProcessHtmlConverter")
@Slf4j
public class HtmlDocumentConverter implements DocumentConverter {

    @Override
    public boolean supports(String filenameOrMime) {
        if (filenameOrMime == null) return false;
        String lower = filenameOrMime.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm") || 
               lower.equals("text/html") || lower.equals("application/xhtml+xml");
    }

    @Override
    public ConversionResult convert(byte[] bytes) throws ConversionException {
        try {
            String html = new String(bytes, StandardCharsets.UTF_8);
            
            // Parse HTML and extract text
            Document doc = Jsoup.parse(html);
            
            // Remove unwanted elements
            doc.select("script,style,noscript,template").remove();
            
            // Get text content
            String text = doc.text();
            
            return new ConversionResult(text);
        } catch (Exception e) {
            throw new ConversionException("Failed to convert HTML document: " + e.getMessage(), e);
        }
    }
}
