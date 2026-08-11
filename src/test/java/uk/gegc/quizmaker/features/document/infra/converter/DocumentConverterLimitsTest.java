package uk.gegc.quizmaker.features.document.infra.converter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document converter resource limits")
class DocumentConverterLimitsTest {

    @TempDir
    Path temporaryDirectory;

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
        DocumentProcessingLimits limits = pdfLimits();
        limits.setMaxPdfPages(1);
        PdfDocumentConverter converter = new PdfDocumentConverter(limits);

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream(twoPagePdf()),
                "book.pdf",
                0L
        )).isInstanceOf(DocumentResourceLimitException.class);
    }

    @Test
    @DisplayName("PDF conversion passes the bounded mixed-memory policy to PDFBox and cleans scratch files")
    void pdfConversionUsesBoundedPolicyAndCleansScratchAfterSuccess() throws Exception {
        DocumentProcessingLimits limits = pdfLimits();
        limits.setMaxPdfMainMemoryBytes(8_192);
        limits.setMaxPdfStorageBytes(16_384);
        PdfScratchSpace scratchSpace = new PdfScratchSpace(limits);
        AtomicReference<Path> operationDirectory = new AtomicReference<>();
        PdfDocumentLoader loader = (inputStream, memoryUsage) -> {
            assertThat(memoryUsage.useMainMemory()).isTrue();
            assertThat(memoryUsage.useTempFile()).isTrue();
            assertThat(memoryUsage.getMaxMainMemoryBytes()).isEqualTo(8_192);
            assertThat(memoryUsage.getMaxStorageBytes()).isEqualTo(16_384);
            operationDirectory.set(memoryUsage.getTempDir().toPath());
            Files.writeString(operationDirectory.get().resolve("PDFBox-test.tmp"), "scratch");
            PDDocument document = new PDDocument();
            document.addPage(new PDPage());
            return document;
        };
        PdfDocumentConverter converter = new PdfDocumentConverter(limits, scratchSpace, loader);

        var converted = converter.convert(new ByteArrayInputStream(new byte[0]), "book.pdf", 0L);

        assertThat(converted.getContentType()).isEqualTo("application/pdf");
        assertThat(converted.getTotalPages()).isEqualTo(1);
        assertThat(operationDirectory.get()).doesNotExist();
    }

    @Test
    @DisplayName("PDF conversion maps a real PDFBox scratch overflow to the resource-limit outcome")
    void pdfConversionRejectsInputAboveScratchStorageLimit() throws Exception {
        DocumentProcessingLimits limits = pdfLimits();
        limits.setMaxPdfMainMemoryBytes(4_096);
        limits.setMaxPdfStorageBytes(4_096);
        PdfDocumentConverter converter = new PdfDocumentConverter(limits);
        byte[] oversizedPdf = Arrays.copyOf(twoPagePdf(), 8_192);

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream(oversizedPdf),
                "book.pdf",
                (long) oversizedPdf.length
        )).isInstanceOf(DocumentResourceLimitException.class)
                .hasMessageContaining("memory and scratch storage limit");

        assertScratchHasNoOperationDirectories(limits);
    }

    @Test
    @DisplayName("PDF conversion removes its scratch directory when the parser fails")
    void pdfConversionCleansScratchAfterParserFailure() {
        DocumentProcessingLimits limits = pdfLimits();
        PdfScratchSpace scratchSpace = new PdfScratchSpace(limits);
        AtomicReference<Path> operationDirectory = new AtomicReference<>();
        PdfDocumentLoader failingLoader = (inputStream, memoryUsage) -> {
            operationDirectory.set(memoryUsage.getTempDir().toPath());
            Files.writeString(operationDirectory.get().resolve("PDFBox-test.tmp"), "scratch");
            throw new IOException("Malformed PDF fixture");
        };
        PdfDocumentConverter converter = new PdfDocumentConverter(limits, scratchSpace, failingLoader);

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream(new byte[]{0x00, 0x01}), "broken.pdf", 2L
        )).isInstanceOf(IOException.class)
                .hasMessage("Malformed PDF fixture");
        assertThat(operationDirectory.get()).doesNotExist();
    }

    @Test
    @DisplayName("PDF conversion removes its scratch directory after real malformed input")
    void pdfConversionCleansScratchAfterMalformedInput() throws Exception {
        DocumentProcessingLimits limits = pdfLimits();
        PdfDocumentConverter converter = new PdfDocumentConverter(limits);

        assertThatThrownBy(() -> converter.convert(
                new ByteArrayInputStream(new byte[]{0x00, 0x01, 0x02, 0x03}),
                "broken.pdf",
                4L
        )).isInstanceOf(IOException.class);

        assertScratchHasNoOperationDirectories(limits);
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

    private DocumentProcessingLimits pdfLimits() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(temporaryDirectory.resolve("documents").toString());
        return limits;
    }

    private void assertScratchHasNoOperationDirectories(DocumentProcessingLimits limits) throws Exception {
        Path root = new PdfScratchSpace(limits).root();
        try (Stream<Path> entries = Files.list(root)) {
            assertThat(entries.filter(path -> path.getFileName().toString().startsWith("pdf-parse-")))
                    .isEmpty();
        }
    }
}
