package uk.gegc.quizmaker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPdfGeneratorTest {

    @Test
    void generateTestPdf_Success(@TempDir Path tempDir) throws IOException {
        // Given
        Path pdfPath = tempDir.resolve("test-document.pdf");

        // When
        TestPdfGenerator.generateTestPdf(pdfPath.toString());

        // Then
        assertTrue(Files.exists(pdfPath));
        assertTrue(Files.size(pdfPath) > 0);

        // Verify it's a valid PDF by checking the first few bytes
        byte[] content = Files.readAllBytes(pdfPath);
        String header = new String(content, 0, Math.min(10, content.length));
        assertTrue(header.startsWith("%PDF-"), "File should start with PDF header");
    }

    @Test
    void generateTestPdfInTestResources_Success() throws IOException {
        // When
        TestPdfGenerator.generateTestPdfInTestResources();

        // Then
        Path testPdfPath = Path.of("src", "test", "resources", "test-documents", "sample-document.pdf");
        assertTrue(Files.exists(testPdfPath), "Test PDF should be created in test resources");
        assertTrue(Files.size(testPdfPath) > 0, "Test PDF should not be empty");
    }
} 