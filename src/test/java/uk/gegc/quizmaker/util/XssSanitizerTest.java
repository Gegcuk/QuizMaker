package uk.gegc.quizmaker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.shared.util.XssSanitizer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XSS Sanitizer Tests")
class XssSanitizerTest {

    private XssSanitizer xssSanitizer;

    @BeforeEach
    void setUp() {
        xssSanitizer = new XssSanitizer();
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldReturnNullForNullInput() {
        assertNull(xssSanitizer.sanitize(null));
    }

    @Test
    @DisplayName("Should return empty string for empty input")
    void shouldReturnEmptyStringForEmptyInput() {
        assertEquals("", xssSanitizer.sanitize(""));
    }

    @Test
    @DisplayName("Should return empty string for whitespace input")
    void shouldReturnEmptyStringForWhitespaceInput() {
        assertEquals("", xssSanitizer.sanitize("   "));
    }

    @Test
    @DisplayName("Should remove script tags")
    void shouldRemoveScriptTags() {
        String input = "Hello <script>alert('xss')</script> World";
        String expected = "Hello  World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should remove script tags with attributes")
    void shouldRemoveScriptTagsWithAttributes() {
        String input = "Hello <script type=\"text/javascript\">alert('xss')</script> World";
        String expected = "Hello  World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should remove javascript protocol")
    void shouldRemoveJavascriptProtocol() {
        String input = "Hello <a href=\"javascript:alert('xss')\">Click me</a> World";
        String expected = "Hello Click me World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should remove on* event handlers")
    void shouldRemoveOnEventHandlers() {
        String input = "Hello <img src=\"test.jpg\" onclick=\"alert('xss')\" onload=\"alert('xss')\" /> World";
        String expected = "Hello  World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should remove all HTML tags")
    void shouldRemoveAllHtmlTags() {
        String input = "Hello <b>Bold</b> <i>Italic</i> <u>Underline</u> World";
        String expected = "Hello Bold Italic Underline World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should handle complex XSS attack")
    void shouldHandleComplexXssAttack() {
        String input = "Hello <script>alert('xss')</script><img src=\"javascript:alert('xss')\" onclick=\"alert('xss')\" /> World";
        String expected = "Hello  World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should preserve safe text")
    void shouldPreserveSafeText() {
        String input = "Hello World! This is safe text with numbers 123 and symbols @#$%";
        assertEquals(input, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should sanitize and truncate within limit")
    void shouldSanitizeAndTruncateWithinLimit() {
        String input = "Hello <script>alert('xss')</script> World";
        String expected = "Hello  World";
        assertEquals(expected, xssSanitizer.sanitizeAndTruncate(input, 50));
    }

    @Test
    @DisplayName("Should sanitize and truncate exceeding limit")
    void shouldSanitizeAndTruncateExceedingLimit() {
        String input = "Hello <script>alert('xss')</script> World with very long text that exceeds the limit";
        String expected = "Hello  World with very long text that exceeds the limit";
        String result = xssSanitizer.sanitizeAndTruncate(input, 20);
        assertEquals(20, result.length());
        assertTrue(result.startsWith("Hello  World"));
    }

    @Test
    @DisplayName("Should handle case insensitive script tags")
    void shouldHandleCaseInsensitiveScriptTags() {
        String input = "Hello <SCRIPT>alert('xss')</SCRIPT> <Script>alert('xss')</Script> World";
        String expected = "Hello   World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should handle case insensitive javascript protocol")
    void shouldHandleCaseInsensitiveJavascriptProtocol() {
        String input = "Hello <a href=\"JAVASCRIPT:alert('xss')\">Click me</a> World";
        String expected = "Hello Click me World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("Should handle case insensitive on* event handlers")
    void shouldHandleCaseInsensitiveOnEventHandlers() {
        String input = "Hello <img src=\"test.jpg\" ONCLICK=\"alert('xss')\" OnLoad=\"alert('xss')\" /> World";
        String expected = "Hello  World";
        assertEquals(expected, xssSanitizer.sanitize(input));
    }
}
