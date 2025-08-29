package uk.gegc.quizmaker.features.conversion.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TxtPassthroughConverterTest {

    @InjectMocks
    private TxtPassthroughConverter converter;

    @Test
    void supports_acceptsTxtExtension() {
        assertThat(converter.supports("document.txt")).isTrue();
        assertThat(converter.supports("file.TXT")).isTrue();
        assertThat(converter.supports("path/to/file.txt")).isTrue();
    }

    @Test
    void supports_acceptsTextPlainMime() {
        assertThat(converter.supports("text/plain")).isTrue();
        assertThat(converter.supports("TEXT/PLAIN")).isTrue();
    }

    @Test
    void supports_rejectsTextHtml() {
        assertThat(converter.supports("text/html")).isFalse();
    }

    @Test
    void supports_rejectsOtherFormats() {
        assertThat(converter.supports("text/xml")).isFalse();
        assertThat(converter.supports("application/pdf")).isFalse();
        assertThat(converter.supports("application/octet-stream")).isFalse();
        assertThat(converter.supports("document.pdf")).isFalse();
        assertThat(converter.supports("file.html")).isFalse();
        assertThat(converter.supports("image.jpg")).isFalse();
        assertThat(converter.supports(null)).isFalse();
    }

    @Test
    void convert_utf8Bytes_ok() throws Exception {
        String expectedText = "Hello, World!";
        byte[] bytes = expectedText.getBytes(StandardCharsets.UTF_8);
        
        ConversionResult result = converter.convert(bytes);
        
        assertThat(result.text()).isEqualTo(expectedText);
    }

    @Test
    void convert_emptyBytes_returnsEmptyString() throws Exception {
        byte[] bytes = new byte[0];
        
        ConversionResult result = converter.convert(bytes);
        
        assertThat(result.text()).isEmpty();
    }

    @Test
    void convert_invalidBytes_usesReplacementNoCrash() throws Exception {
        // Create invalid UTF-8 bytes
        byte[] invalidBytes = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
        
        ConversionResult result = converter.convert(invalidBytes);
        
        // Should not throw exception, should use replacement characters
        assertThat(result.text()).isNotNull();
    }

    @Test
    void convert_unicodeText_preserved() throws Exception {
        String unicodeText = "Hello 世界! Привет!";
        byte[] bytes = unicodeText.getBytes(StandardCharsets.UTF_8);
        
        ConversionResult result = converter.convert(bytes);
        
        assertThat(result.text()).isEqualTo(unicodeText);
    }

    @Test
    void convert_largeText_handled() throws Exception {
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeText.append("Line ").append(i).append("\n");
        }
        String expectedText = largeText.toString();
        byte[] bytes = expectedText.getBytes(StandardCharsets.UTF_8);
        
        ConversionResult result = converter.convert(bytes);
        
        assertThat(result.text()).isEqualTo(expectedText);
        assertThat(result.text().length()).isEqualTo(expectedText.length());
    }
}
