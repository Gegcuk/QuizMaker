package uk.gegc.quizmaker.features.conversion.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Basic EPUB document converter.
 * Extracts text from EPUB files by reading HTML content from the ZIP structure.
 * This is a simplified implementation that reads all HTML files in the EPUB.
 */
@Component("documentProcessEpubConverter")
@Slf4j
@RequiredArgsConstructor
public class EpubDocumentConverter implements DocumentConverter {

    private final HtmlDocumentConverter htmlConverter;

    @Override
    public boolean supports(String filenameOrMime) {
        if (filenameOrMime == null) return false;
        String lower = filenameOrMime.toLowerCase();
        return lower.endsWith(".epub") || lower.equals("application/epub+zip");
    }

    @Override
    public ConversionResult convert(byte[] bytes) throws ConversionException {
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            StringBuilder fullText = new StringBuilder();
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipIn.closeEntry();
                    continue;
                }
                
                String entryName = entry.getName().toLowerCase();
                if (entryName.endsWith(".html") || entryName.endsWith(".xhtml") || entryName.endsWith(".htm")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    int bytesRead;
                    while ((bytesRead = zipIn.read(buffer)) > 0) {
                        out.write(buffer, 0, bytesRead);
                    }
                    
                    ConversionResult htmlResult = htmlConverter.convert(out.toByteArray());
                    if (!htmlResult.text().isBlank()) {
                        fullText.append(htmlResult.text()).append("\n\n");
                    }
                }
                zipIn.closeEntry();
            }
            
            String text = fullText.toString().trim();
            return new ConversionResult(text);
            
        } catch (IOException e) {
            throw new ConversionException("Failed to convert EPUB document: " + e.getMessage(), e);
        }
    }
}
