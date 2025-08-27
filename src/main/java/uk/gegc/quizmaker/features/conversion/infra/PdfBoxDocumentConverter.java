package uk.gegc.quizmaker.features.conversion.infra;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;

import java.io.ByteArrayInputStream;

/**
 * PDF document converter using Apache PDFBox.
 * Extracts plain text from PDF documents.
 */
@Component("documentProcessPdfBoxConverter")
@Slf4j
public class PdfBoxDocumentConverter implements DocumentConverter {

    @Override
    public boolean supports(String filenameOrMime) {
        if (filenameOrMime == null) return false;
        String lower = filenameOrMime.toLowerCase();
        return lower.endsWith(".pdf") || lower.equals("application/pdf");
    }

    @Override
    public ConversionResult convert(byte[] bytes) throws ConversionException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            
            log.debug("Converted PDF document: {} bytes -> {} characters", bytes.length, text.length());
            return new ConversionResult(text);
        } catch (Exception e) {
            throw new ConversionException("Failed to convert PDF document: " + e.getMessage(), e);
        }
    }
}
