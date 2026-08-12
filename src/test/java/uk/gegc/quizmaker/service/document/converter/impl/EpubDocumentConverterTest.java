package uk.gegc.quizmaker.service.document.converter.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.infra.converter.EpubDocumentConverter;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Legacy EPUB document converter")
@Execution(ExecutionMode.CONCURRENT)
class EpubDocumentConverterTest {

    private final DocumentConverter converter = new EpubDocumentConverter(DocumentProcessingLimits.defaults());
    private final DocumentConverterFactory converterFactory = new DocumentConverterFactory(List.of(converter));

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {"application/epub+zip", "application/epub", "application/x-epub"})
    @DisplayName("Accepts every supported EPUB content type")
    void canConvertAcceptsSupportedEpubContentTypes(String contentType) {
        assertThat(converter.canConvert(contentType, "document.bin")).isTrue();
    }

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {"document.epub", "DOCUMENT.EPUB"})
    @DisplayName("Accepts EPUB filenames when the content type is generic")
    void canConvertAcceptsEpubExtension(String filename) {
        assertThat(converter.canConvert("application/octet-stream", filename)).isTrue();
    }

    @Test
    @DisplayName("Rejects content with neither an EPUB type nor extension")
    void canConvertRejectsNonEpubInput() {
        assertThat(converter.canConvert("application/pdf", "document.pdf")).isFalse();
    }

    @Test
    @DisplayName("Preserves content-type precedence when the filename extension disagrees")
    void canConvertAcceptsEpubContentTypeWithWrongExtension() {
        assertThat(converter.canConvert("application/epub+zip", "document.pdf")).isTrue();
    }

    @Test
    @DisplayName("Exposes the complete stable EPUB content-type contract")
    void getSupportedContentTypesReturnsEpubContentTypes() {
        assertThat(converter.getSupportedContentTypes()).containsExactly(
                "application/epub+zip",
                "application/epub",
                "application/x-epub"
        );
    }

    @Test
    @DisplayName("Exposes only the EPUB filename extension")
    void getSupportedExtensionsReturnsEpubExtension() {
        assertThat(converter.getSupportedExtensions()).containsExactly(".epub");
    }

    @Test
    @DisplayName("Exposes the stable EPUB converter identifier")
    void getConverterTypeReturnsEpubConverterType() {
        assertThat(converter.getConverterType()).isEqualTo("EPUB_DOCUMENT_CONVERTER");
    }

    @Test
    @DisplayName("Extracts bounded content and structure from a valid in-memory EPUB")
    void convertValidEpubExtractsContentAndStructure() throws Exception {
        byte[] epub = createEpub("Testing EPUB", "Chapter 1", "Bounded fixture content");

        ConvertedDocument result = converter.convert(
                new ByteArrayInputStream(epub),
                "fixture.epub",
                (long) epub.length
        );

        assertThat(result.getOriginalFilename()).isEqualTo("fixture.epub");
        assertThat(result.getContentType()).isEqualTo("application/epub+zip");
        assertThat(result.getFileSize()).isEqualTo((long) epub.length);
        assertThat(result.getConverterType()).isEqualTo("EPUB_DOCUMENT_CONVERTER");
        assertThat(result.getFullContent())
                .contains("Chapter 1")
                .contains("Bounded fixture content");
        assertThat(result.getChapters()).isNotEmpty();
    }

    @Test
    @DisplayName("Preserves legacy graceful extraction for malformed EPUB content")
    void convertInvalidEpubContentUsesSingleDocumentChapter() throws Exception {
        byte[] invalidContent = "This is not a valid EPUB file".getBytes(StandardCharsets.UTF_8);

        ConvertedDocument result = converter.convert(
                new ByteArrayInputStream(invalidContent),
                "invalid.epub",
                (long) invalidContent.length
        );

        assertThat(result.getOriginalFilename()).isEqualTo("invalid.epub");
        assertThat(result.getContentType()).isEqualTo("application/epub+zip");
        assertThat(result.getFileSize()).isEqualTo((long) invalidContent.length);
        assertThat(result.getConverterType()).isEqualTo("EPUB_DOCUMENT_CONVERTER");
        assertThat(result.getFullContent().trim()).isEqualTo("This is not a valid EPUB file");
        assertThat(result.getChapters()).singleElement()
                .satisfies(chapter -> assertThat(chapter.getTitle()).isEqualTo("Document"));
    }

    @Test
    @DisplayName("Rejects extracted EPUB text beyond the server-owned character limit")
    void convertRejectsExtractedTextBeyondConfiguredLimit() throws IOException {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setMaxExtractedCharacters(8);
        DocumentConverter boundedConverter = new EpubDocumentConverter(limits);
        byte[] content = createEpub(
                "Bounded EPUB",
                "Chapter 1",
                "Content longer than eight extracted characters"
        );

        assertThatThrownBy(() -> boundedConverter.convert(
                new ByteArrayInputStream(content),
                "oversized.epub",
                (long) content.length
        )).isInstanceOf(DocumentResourceLimitException.class);
    }

    @Test
    @DisplayName("Rejects a missing input stream before producing a converted document")
    void convertNullInputStreamThrowsException() {
        assertThatThrownBy(() -> converter.convert(null, "missing.epub", 100L))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Factory selects EPUB by canonical content type")
    void factoryFindConverterSelectsEpubByContentType() {
        DocumentConverter foundConverter = converterFactory.findConverter(
                "application/epub+zip",
                "document.epub"
        );

        assertThat(foundConverter).isSameAs(converter);
    }

    @Test
    @DisplayName("Factory selects EPUB by extension for generic content")
    void factoryFindConverterSelectsEpubByExtension() {
        DocumentConverter foundConverter = converterFactory.findConverter(
                "application/octet-stream",
                "document.epub"
        );

        assertThat(foundConverter).isSameAs(converter);
    }

    private byte[] createEpub(String title, String chapter, String content) throws IOException {
        byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(mimetype);

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream output = new ZipOutputStream(bytes)) {
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
                        <dc:identifier id="book-id">issue-735-fixture</dc:identifier>
                        <dc:title>%s</dc:title>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """.formatted(title));
            writeEntry(output, "OEBPS/chapter.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>%s</title></head>
                      <body><h1>%s</h1><p>%s</p></body>
                    </html>
                    """.formatted(title, chapter, content));
            output.finish();
            return bytes.toByteArray();
        }
    }

    private void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
