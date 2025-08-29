package uk.gegc.quizmaker.features.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.infra.HtmlDocumentConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HtmlDocumentConverterTest {

    private HtmlDocumentConverter converter;

    @BeforeEach
    void setUp() {
        converter = new HtmlDocumentConverter();
    }

    @Test
    void supports_acceptsHtmlExtAndMimes() {
        // HTML extensions and MIME types
        assertThat(converter.supports("file.html")).isTrue();
        assertThat(converter.supports("file.htm")).isTrue();
        assertThat(converter.supports("text/html")).isTrue();
        assertThat(converter.supports("application/xhtml+xml")).isTrue();
        
        // Unsupported formats
        assertThat(converter.supports("file.txt")).isFalse();
        assertThat(converter.supports("file.pdf")).isFalse();
        assertThat(converter.supports(null)).isFalse();
    }

    @Test
    void convert_stripsScriptAndStyle_returnsPlainText() throws ConversionException {
        // Given
        String htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Test Document</title>
                <script>console.log("This should be removed");</script>
                <style>body { color: red; }</style>
            </head>
            <body>
                <h1>Sample HTML Document</h1>
                <p>This is a paragraph with <strong>bold text</strong> and <em>italic text</em>.</p>
                <div>
                    <p>Another paragraph with <a href="#">links</a> and <span>spans</span>.</p>
                </div>
                <noscript>This should also be removed</noscript>
            </body>
            </html>
            """;
        
        // When
        ConversionResult result = converter.convert(htmlContent.getBytes());
        
        // Then
        String expectedText = "Test Document Sample HTML Document This is a paragraph with bold text and italic text. Another paragraph with links and spans.";
        assertThat(result.text()).isEqualTo(expectedText);
    }

    @Test
    void convert_handlesEntitiesAndUnicode() throws ConversionException {
        // Given
        String htmlContent = """
            <html>
            <body>
                <p>HTML entities: &amp; &lt; &gt; &quot; &apos;</p>
                <p>Unicode: café résumé naïve</p>
                <p>Emojis: 🌍 🚀 💻</p>
            </body>
            </html>
            """;
        
        // When
        ConversionResult result = converter.convert(htmlContent.getBytes());
        
        // Then
        String expectedText = "HTML entities: & < > \" ' Unicode: café résumé naïve Emojis: 🌍 🚀 💻";
        assertThat(result.text()).isEqualTo(expectedText);
    }

    @Test
    void convert_handlesXhtmlMime() throws ConversionException {
        // Given
        String xhtmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <title>XHTML Document</title>
            </head>
            <body>
                <p>This is XHTML content.</p>
            </body>
            </html>
            """;
        
        // When
        ConversionResult result = converter.convert(xhtmlContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("XHTML Document This is XHTML content.");
    }

    @Test
    void convert_emptyHtml_returnsEmptyText() throws ConversionException {
        // Given
        String emptyHtml = "<html><body></body></html>";
        
        // When
        ConversionResult result = converter.convert(emptyHtml.getBytes());
        
        // Then
        assertThat(result.text()).isEmpty();
    }

    @Test
    void convert_robustHandlingOfMalformedHtml() throws ConversionException {
        // Given - Test that the converter handles malformed HTML gracefully
        String malformedHtml = "<html><body><p>Unclosed tag<div>More content";
        
        // When
        ConversionResult result = converter.convert(malformedHtml.getBytes());
        
        // Then - Should still extract text content despite malformed HTML
        assertThat(result.text()).contains("Unclosed tag");
        assertThat(result.text()).contains("More content");
    }

    @Test
    void convert_removesAllUnwantedElements() throws ConversionException {
        // Given
        String htmlContent = """
            <html>
            <head>
                <script>alert('script');</script>
                <style>body { color: red; }</style>
                <noscript>noscript content</noscript>
                <template>template content</template>
            </head>
            <body>
                <p>Valid content</p>
            </body>
            </html>
            """;
        
        // When
        ConversionResult result = converter.convert(htmlContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Valid content");
    }
}
