package uk.gegc.quizmaker.features.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.infra.EpubDocumentConverter;
import uk.gegc.quizmaker.features.conversion.infra.HtmlDocumentConverter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpubDocumentConverterTest {

    @Mock
    private HtmlDocumentConverter htmlConverter;

    private EpubDocumentConverter converter;

    @BeforeEach
    void setUp() {
        converter = new EpubDocumentConverter(htmlConverter);
    }

    @Test
    void supports_acceptsEpubExtAndMime() {
        // EPUB extensions and MIME
        assertThat(converter.supports("file.epub")).isTrue();
        assertThat(converter.supports("application/epub+zip")).isTrue();
        
        // Unsupported formats
        assertThat(converter.supports("file.txt")).isFalse();
        assertThat(converter.supports("file.pdf")).isFalse();
        assertThat(converter.supports(null)).isFalse();
    }

    @Test
    void convert_readsMultipleHtmlEntries_concatenatesWithSpacing() throws IOException, ConversionException {
        // Given
        byte[] epubBytes = createTestEpub();
        when(htmlConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("Page 1 content"))
                .thenReturn(new ConversionResult("Page 2 content"));
        
        // When
        ConversionResult result = converter.convert(epubBytes);
        
        // Then
        assertThat(result.text()).isEqualTo("Page 1 content\n\nPage 2 content");
    }

    @Test
    void convert_ignoresNonHtmlEntries() throws IOException, ConversionException {
        // Given
        byte[] epubBytes = createTestEpubWithNonHtmlEntries();
        when(htmlConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("HTML content"));
        
        // When
        ConversionResult result = converter.convert(epubBytes);
        
        // Then
        assertThat(result.text()).isEqualTo("HTML content");
    }

    @Test
    void convert_robustHandlingOfCorruptedZip() throws ConversionException {
        // Given - Test that the converter handles corrupted ZIP gracefully
        byte[] corruptBytes = "This is not a ZIP file".getBytes();
        
        // When
        ConversionResult result = converter.convert(corruptBytes);
        
        // Then - Should return empty text for corrupted ZIP
        assertThat(result.text()).isEmpty();
    }

    @Test
    void convert_doesNotReadPastEntry_boundaries() throws IOException, ConversionException {
        // Given - Create EPUB with multiple entries to test boundary reading
        byte[] epubBytes = createTestEpubWithMultipleEntries();
        when(htmlConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("Entry 1"))
                .thenReturn(new ConversionResult("Entry 2"));
        
        // When
        ConversionResult result = converter.convert(epubBytes);
        
        // Then - Should read each entry separately without crossing boundaries
        assertThat(result.text()).isEqualTo("Entry 1\n\nEntry 2");
    }

    @Test
    void convert_emptyEpub_returnsEmptyText() throws IOException, ConversionException {
        // Given
        byte[] emptyEpub = createEmptyEpub();
        
        // When
        ConversionResult result = converter.convert(emptyEpub);
        
        // Then
        assertThat(result.text()).isEmpty();
    }

    @Test
    void convert_skipsBlankHtmlContent() throws IOException, ConversionException {
        // Given
        byte[] epubBytes = createTestEpub();
        when(htmlConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult(""))
                .thenReturn(new ConversionResult("Valid content"));
        
        // When
        ConversionResult result = converter.convert(epubBytes);
        
        // Then - Should skip blank content and only include valid content
        assertThat(result.text()).isEqualTo("Valid content");
    }

    private byte[] createTestEpub() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add HTML entry
            ZipEntry htmlEntry = new ZipEntry("page1.html");
            zos.putNextEntry(htmlEntry);
            zos.write("<html><body>Page 1</body></html>".getBytes());
            zos.closeEntry();
            
            // Add another HTML entry
            ZipEntry htmlEntry2 = new ZipEntry("page2.html");
            zos.putNextEntry(htmlEntry2);
            zos.write("<html><body>Page 2</body></html>".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createTestEpubWithNonHtmlEntries() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add non-HTML entry (should be ignored)
            ZipEntry cssEntry = new ZipEntry("style.css");
            zos.putNextEntry(cssEntry);
            zos.write("body { color: red; }".getBytes());
            zos.closeEntry();
            
            // Add HTML entry (should be processed)
            ZipEntry htmlEntry = new ZipEntry("page.html");
            zos.putNextEntry(htmlEntry);
            zos.write("<html><body>Content</body></html>".getBytes());
            zos.closeEntry();
            
            // Add image entry (should be ignored)
            ZipEntry imgEntry = new ZipEntry("image.jpg");
            zos.putNextEntry(imgEntry);
            zos.write("fake image data".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createTestEpubWithMultipleEntries() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add multiple HTML entries to test boundary reading
            for (int i = 1; i <= 2; i++) {
                ZipEntry entry = new ZipEntry("page" + i + ".html");
                zos.putNextEntry(entry);
                zos.write(("<html><body>Entry " + i + "</body></html>").getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private byte[] createEmptyEpub() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Empty EPUB with no entries
        }
        return baos.toByteArray();
    }
}
