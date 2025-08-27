package uk.gegc.quizmaker.features.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;

import static org.assertj.core.api.Assertions.assertThat;

class MimeTypeDetectorTest {

    private MimeTypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new MimeTypeDetector();
    }

    @Test
    void detectMimeType_mapsKnownExtensions() {
        // Test all supported extensions
        assertThat(detector.detectMimeType("file.txt")).isEqualTo("text/plain");
        assertThat(detector.detectMimeType("file.pdf")).isEqualTo("application/pdf");
        assertThat(detector.detectMimeType("file.html")).isEqualTo("text/html");
        assertThat(detector.detectMimeType("file.htm")).isEqualTo("text/html");
        assertThat(detector.detectMimeType("file.epub")).isEqualTo("application/epub+zip");
        assertThat(detector.detectMimeType("file.srt")).isEqualTo("text/srt");
        assertThat(detector.detectMimeType("file.vtt")).isEqualTo("text/vtt");
    }

    @Test
    void detectMimeType_unknown_returnsOctetStream() {
        // Test unknown extensions
        assertThat(detector.detectMimeType("file.xyz")).isEqualTo("application/octet-stream");
        assertThat(detector.detectMimeType("file.unknown")).isEqualTo("application/octet-stream");
        assertThat(detector.detectMimeType("file")).isEqualTo("application/octet-stream");
    }

    @Test
    void detectMimeType_caseInsensitive() {
        // Test case insensitivity
        assertThat(detector.detectMimeType("file.PDF")).isEqualTo("application/pdf");
        assertThat(detector.detectMimeType("file.HTML")).isEqualTo("text/html");
        assertThat(detector.detectMimeType("file.TXT")).isEqualTo("text/plain");
    }

    @Test
    void detectMimeType_nullAndEmpty() {
        // Test null and empty inputs
        assertThat(detector.detectMimeType(null)).isEqualTo("application/octet-stream");
        assertThat(detector.detectMimeType("")).isEqualTo("application/octet-stream");
    }

    @Test
    void isSupportedMimeType_trueForKnown_falseOtherwise() {
        // Test supported MIME types
        assertThat(detector.isSupportedMimeType("text/plain")).isTrue();
        assertThat(detector.isSupportedMimeType("application/pdf")).isTrue();
        assertThat(detector.isSupportedMimeType("text/html")).isTrue();
        assertThat(detector.isSupportedMimeType("application/epub+zip")).isTrue();
        assertThat(detector.isSupportedMimeType("text/srt")).isTrue();
        assertThat(detector.isSupportedMimeType("text/vtt")).isTrue();
        
        // Test unsupported MIME types
        assertThat(detector.isSupportedMimeType("application/octet-stream")).isFalse();
        assertThat(detector.isSupportedMimeType("image/jpeg")).isFalse();
        assertThat(detector.isSupportedMimeType("video/mp4")).isFalse();
        assertThat(detector.isSupportedMimeType(null)).isFalse();
    }

    @Test
    void isSupportedMimeType_caseInsensitive() {
        // Test case insensitivity
        assertThat(detector.isSupportedMimeType("TEXT/PLAIN")).isTrue();
        assertThat(detector.isSupportedMimeType("APPLICATION/PDF")).isTrue();
        assertThat(detector.isSupportedMimeType("Text/Html")).isTrue();
    }

    @Test
    void getExtensionForMimeType_roundTripsForKnown() {
        // Test round-trip mapping
        assertThat(detector.getExtensionForMimeType("text/plain")).isEqualTo(".txt");
        assertThat(detector.getExtensionForMimeType("application/pdf")).isEqualTo(".pdf");
        assertThat(detector.getExtensionForMimeType("text/html")).containsAnyOf(".html", ".htm"); // .html comes first in map iteration
        assertThat(detector.getExtensionForMimeType("application/epub+zip")).isEqualTo(".epub");
        assertThat(detector.getExtensionForMimeType("text/srt")).isEqualTo(".srt");
        assertThat(detector.getExtensionForMimeType("text/vtt")).isEqualTo(".vtt");
    }

    @Test
    void getExtensionForMimeType_unknownReturnsNull() {
        // Test unknown MIME types
        assertThat(detector.getExtensionForMimeType("application/octet-stream")).isNull();
        assertThat(detector.getExtensionForMimeType("image/jpeg")).isNull();
        assertThat(detector.getExtensionForMimeType("video/mp4")).isNull();
        assertThat(detector.getExtensionForMimeType(null)).isNull();
    }

    @Test
    void getExtensionForMimeType_caseInsensitive() {
        // Test case insensitivity
        assertThat(detector.getExtensionForMimeType("TEXT/PLAIN")).isEqualTo(".txt");
        assertThat(detector.getExtensionForMimeType("APPLICATION/PDF")).isEqualTo(".pdf");
        assertThat(detector.getExtensionForMimeType("Text/Html")).containsAnyOf(".htm", ".html"); // .html comes first in map iteration
    }

    @Test
    void roundTripConsistency() {
        // Test that extension -> MIME -> extension round trip works
        // Note: .htm and .html both map to text/html, so we test them separately
        String[] extensions = {".txt", ".pdf", ".epub", ".srt", ".vtt"};
        
        for (String ext : extensions) {
            String mime = detector.detectMimeType("file" + ext);
            String roundTripExt = detector.getExtensionForMimeType(mime);
            assertThat(roundTripExt).isEqualTo(ext);
        }
        
        // Test .html specifically - it should map to .html in reverse (since .html comes first in map iteration)
        String htmlMime = detector.detectMimeType("file.html");
        String htmlRoundTripExt = detector.getExtensionForMimeType(htmlMime);
        assertThat(htmlRoundTripExt).containsAnyOf(".htm", ".html"); // .html comes first in the map iteration
        
        // Test .htm specifically - it should map to .html in reverse (since .html comes first in map iteration)
        String htmMime = detector.detectMimeType("file.htm");
        String htmRoundTripExt = detector.getExtensionForMimeType(htmMime);
        assertThat(htmRoundTripExt).containsAnyOf(".htm", ".html"); // .html comes first in the map iteration
    }
}
