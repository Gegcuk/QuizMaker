package uk.gegc.quizmaker.features.document.application.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.infra.isolation.LocalDocumentParserWorkerFactory;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Isolated document parser compatibility")
class DocumentParserWorkerIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    private BoundedDocumentParseExecutor executor;

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Converts a legacy text MIME alias in a child JVM without changing the result contract")
    void convertsLegacyTextAliasInChildProcess() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path source = copyFixture("sample-text.txt", "text.upload", limits);
        executor = executor(limits);

        ConvertedDocument document = executor.execute("owner", new DocumentParseRequest(
                source, "legacy-notes.text", "text/txt", Files.size(source)));

        assertThat(document.getConverterType()).isEqualTo("TEXT_DOCUMENT_CONVERTER");
        assertThat(document.getContentType()).isEqualTo("text/plain");
        assertThat(document.getOriginalFilename()).isEqualTo("legacy-notes.text");
        assertThat(document.getFileSize()).isEqualTo(Files.size(source));
        assertThat(document.getFullContent()).contains("Chapter");
        assertThat(document.getChapters()).isNotEmpty();
    }

    @Test
    @DisplayName("Converts a PDF selected by its legacy filename when stored MIME metadata is generic")
    void convertsPdfSelectedByLegacyFilenameInChildProcess() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path source = copyFixture("sample-document.pdf", "pdf.upload", limits);
        executor = executor(limits);

        ConvertedDocument document = executor.execute("owner", new DocumentParseRequest(
                source, "legacy-document.pdf", "application/octet-stream", Files.size(source)));

        assertThat(document.getConverterType()).isEqualTo("PDF_DOCUMENT_CONVERTER");
        assertThat(document.getContentType()).isEqualTo("application/pdf");
        assertThat(document.getOriginalFilename()).isEqualTo("legacy-document.pdf");
        assertThat(document.getFileSize()).isEqualTo(Files.size(source));
        assertThat(document.getTotalPages()).isEqualTo(1);
        assertThat(document.getFullContent()).contains("Programming Fundamentals");
        assertThat(document.getChapters()).hasSize(5);
    }

    @Test
    @DisplayName("Converts a valid EPUB with its legacy MIME alias in a child JVM")
    void convertsLegacyEpubAliasInChildProcess() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path source = storageRoot(limits).resolve("epub.upload");
        writeEpub(source);
        executor = executor(limits);

        ConvertedDocument document = executor.execute("owner", new DocumentParseRequest(
                source, "legacy-book.epub", "application/x-epub", Files.size(source)));

        assertThat(document.getConverterType()).isEqualTo("EPUB_DOCUMENT_CONVERTER");
        assertThat(document.getContentType()).isEqualTo("application/epub+zip");
        assertThat(document.getOriginalFilename()).isEqualTo("legacy-book.epub");
        assertThat(document.getFileSize()).isEqualTo(Files.size(source));
        assertThat(document.getFullContent()).contains("Isolated parser compatibility");
        assertThat(document.getChapters()).isNotEmpty();
    }

    @Test
    @DisplayName("Rejects bounded worker output overflow and immediately reuses parser capacity")
    void rejectsWorkerOutputOverflowAndReusesCapacity() throws IOException {
        DocumentProcessingLimits limits = limits();
        limits.setMaxExtractedCharacters(900);
        limits.setParserWorkerMaxOutputBytes(1_024);
        Path oversized = Files.writeString(
                storageRoot(limits).resolve("oversized.upload"),
                "Chapter 1\n" + "x".repeat(800));
        Path replacement = Files.writeString(
                storageRoot(limits).resolve("replacement.upload"), "Short notes\n");
        executor = executor(limits);

        assertThatThrownBy(() -> executor.execute("owner", new DocumentParseRequest(
                oversized, "oversized.txt", "text/plain", Files.size(oversized))))
                .isInstanceOf(DocumentResourceLimitException.class)
                .hasMessage("Converted document output exceeds the configured limit");

        ConvertedDocument result = executor.execute("owner", new DocumentParseRequest(
                replacement, "replacement.txt", "text/plain", Files.size(replacement)));
        assertThat(result.getFullContent()).isEqualTo("Short notes\n");
        assertThat(oversized).isRegularFile();
    }

    private BoundedDocumentParseExecutor executor(DocumentProcessingLimits limits) {
        BoundedDocumentParseExecutor result = new BoundedDocumentParseExecutor(
                limits,
                new LocalDocumentParserWorkerFactory(limits, Clock.systemUTC()),
                new MicrometerDocumentParserWorkerMetrics(new SimpleMeterRegistry())
        );
        result.initialize();
        return result;
    }

    private DocumentProcessingLimits limits() throws IOException {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(Files.createDirectories(temporaryDirectory.resolve("documents")).toString());
        limits.setMaxExtractedCharacters(100_000);
        limits.setParserWorkerMaxHeapBytes(128L * 1024 * 1024);
        limits.setParserWorkerMaxOutputBytes(4L * 1024 * 1024);
        limits.setParseTimeout(Duration.ofSeconds(20));
        limits.setParserTerminationGrace(Duration.ofMillis(200));
        limits.setParserForceKillTimeout(Duration.ofSeconds(2));
        limits.setParserShutdownTimeout(Duration.ofSeconds(3));
        return limits;
    }

    private Path copyFixture(String fixture, String target, DocumentProcessingLimits limits) throws IOException {
        return Files.copy(
                Path.of("src/test/resources/test-documents", fixture),
                storageRoot(limits).resolve(target)
        );
    }

    private Path storageRoot(DocumentProcessingLimits limits) {
        return Path.of(limits.getStorageRoot());
    }

    private void writeEpub(Path target) throws IOException {
        byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(mimetype);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry mimetypeEntry = new ZipEntry("mimetype");
            mimetypeEntry.setMethod(ZipEntry.STORED);
            mimetypeEntry.setSize(mimetype.length);
            mimetypeEntry.setCompressedSize(mimetype.length);
            mimetypeEntry.setCrc(crc.getValue());
            output.putNextEntry(mimetypeEntry);
            output.write(mimetype);
            output.closeEntry();

            writeEntry(output, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            writeEntry(output, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">isolation-fixture</dc:identifier>
                        <dc:title>Parser Fixture</dc:title>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """);
            writeEntry(output, "OEBPS/chapter.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>Chapter 1</title></head>
                      <body><h1>Chapter 1</h1><p>Isolated parser compatibility</p></body>
                    </html>
                    """);
        }
    }

    private void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
