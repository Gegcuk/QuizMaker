package uk.gegc.quizmaker.features.document.infra.converter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document converter resource limits")
class DocumentConverterLimitsTest {

    @Test
    @DisplayName("Text conversion stops before retaining content above the extracted-character limit")
    void textConversionRejectsContentAboveCharacterLimit() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setMaxExtractedCharacters(5);
        TextDocumentConverter converter = new TextDocumentConverter(limits);

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)),
                "notes.txt",
                5L
        )).isInstanceOf(DocumentResourceLimitException.class);
    }

    @Test
    @DisplayName("PDF conversion rejects documents above the configured page limit before text extraction")
    void pdfConversionRejectsDocumentAbovePageLimit() throws Exception {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setMaxPdfPages(1);
        PdfDocumentConverter converter = new PdfDocumentConverter(limits);

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream(twoPagePdf()),
                "book.pdf",
                0L
        )).isInstanceOf(DocumentResourceLimitException.class);
    }

    @Test
    @DisplayName("Text conversion rejects malformed UTF-8 beyond the detected file header")
    void textConversionRejectsMalformedUtf8BeyondInitialDetectionBytes() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        TextDocumentConverter converter = new TextDocumentConverter(limits);
        byte[] validPrefix = "a".repeat(20_000).getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[validPrefix.length + 2];
        System.arraycopy(validPrefix, 0, content, 0, validPrefix.length);
        content[content.length - 2] = (byte) 0xC3;
        content[content.length - 1] = (byte) 0x28;

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream(content),
                "notes.txt",
                (long) content.length
        )).isInstanceOf(DocumentTypeMismatchException.class);
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
