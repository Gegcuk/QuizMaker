package uk.gegc.quizmaker.service.document.converter.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;
import uk.gegc.quizmaker.service.document.converter.DocumentConverter;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class PdfDocumentConverterTest {

    private final DocumentConverter converter = new PdfDocumentConverter();

    @Test
    void canConvert_PdfContentType_ReturnsTrue() {
        // Act
        boolean result = converter.canConvert("application/pdf", "test.pdf");

        // Assert
        assertTrue(result);
    }

    @Test
    void canConvert_PdfExtension_ReturnsTrue() {
        // Act
        boolean result = converter.canConvert("application/octet-stream", "test.pdf");

        // Assert
        assertTrue(result);
    }

    @Test
    void canConvert_NonPdfContentType_ReturnsFalse() {
        // Act
        boolean result = converter.canConvert("text/plain", "test.txt");

        // Assert
        assertFalse(result);
    }

    @Test
    void canConvert_NonPdfExtension_ReturnsFalse() {
        // Act
        boolean result = converter.canConvert("text/plain", "test.txt");

        // Assert
        assertFalse(result);
    }

    @Test
    void getSupportedContentTypes_ReturnsPdfContentType() {
        // Act
        List<String> result = converter.getSupportedContentTypes();

        // Assert
        assertEquals(Arrays.asList("application/pdf"), result);
    }

    @Test
    void getSupportedExtensions_ReturnsPdfExtension() {
        // Act
        List<String> result = converter.getSupportedExtensions();

        // Assert
        assertEquals(Arrays.asList(".pdf"), result);
    }

    @Test
    void getConverterType_ReturnsPdfConverterType() {
        // Act
        String result = converter.getConverterType();

        // Assert
        assertEquals("PDF_DOCUMENT_CONVERTER", result);
    }

    @Test
    void convert_ValidPdfContent_ReturnsConvertedDocument() throws Exception {
        // Arrange
        // Create a simple PDF content (this is a minimal PDF structure)
        byte[] pdfContent = createSimplePdfContent();
        String filename = "test.pdf";
        Long fileSize = (long) pdfContent.length;

        // Act
        ConvertedDocument result = converter.convert(new ByteArrayInputStream(pdfContent), filename, fileSize);

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertEquals("application/pdf", result.getContentType());
        assertEquals(fileSize, result.getFileSize());
        assertEquals("PDF_DOCUMENT_CONVERTER", result.getConverterType());
        assertNotNull(result.getFullContent());
        assertNotNull(result.getChapters());
    }

    @Test
    void convert_InvalidPdfContent_ThrowsException() {
        // Arrange - Create content that definitely won't be a valid PDF
        byte[] invalidContent = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05}; // Random bytes
        String filename = "test.pdf";
        Long fileSize = (long) invalidContent.length;

        // Act & Assert
        assertThrows(Exception.class, () -> {
            converter.convert(new ByteArrayInputStream(invalidContent), filename, fileSize);
        });
    }

    @Test
    void convert_NullInputStream_ThrowsException() {
        // Arrange
        String filename = "test.pdf";
        Long fileSize = 100L;

        // Act & Assert
        assertThrows(Exception.class, () -> {
            converter.convert(null, filename, fileSize);
        });
    }

    /**
     * Creates a minimal valid PDF content for testing
     * This is a very basic PDF structure that should be parseable
     */
    private byte[] createSimplePdfContent() {
        // This is a minimal PDF content that PDFBox can parse
        String pdfContent = "%PDF-1.4\n" +
                "1 0 obj\n" +
                "<<\n" +
                "/Type /Catalog\n" +
                "/Pages 2 0 R\n" +
                ">>\n" +
                "endobj\n" +
                "2 0 obj\n" +
                "<<\n" +
                "/Type /Pages\n" +
                "/Kids [3 0 R]\n" +
                "/Count 1\n" +
                ">>\n" +
                "endobj\n" +
                "3 0 obj\n" +
                "<<\n" +
                "/Type /Page\n" +
                "/Parent 2 0 R\n" +
                "/MediaBox [0 0 612 792]\n" +
                "/Contents 4 0 R\n" +
                ">>\n" +
                "endobj\n" +
                "4 0 obj\n" +
                "<<\n" +
                "/Length 44\n" +
                ">>\n" +
                "stream\n" +
                "BT\n" +
                "/F1 12 Tf\n" +
                "72 720 Td\n" +
                "(Hello World) Tj\n" +
                "ET\n" +
                "endstream\n" +
                "endobj\n" +
                "xref\n" +
                "0 5\n" +
                "0000000000 65535 f \n" +
                "0000000009 00000 n \n" +
                "0000000058 00000 n \n" +
                "0000000115 00000 n \n" +
                "0000000204 00000 n \n" +
                "trailer\n" +
                "<<\n" +
                "/Size 5\n" +
                "/Root 1 0 R\n" +
                ">>\n" +
                "startxref\n" +
                "364\n" +
                "%%EOF\n";

        return pdfContent.getBytes();
    }
} 