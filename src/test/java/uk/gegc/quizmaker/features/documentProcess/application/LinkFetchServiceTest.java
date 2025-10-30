package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.documentProcess.config.LinkFetchConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.LinkFetchException;
import uk.gegc.quizmaker.features.documentProcess.domain.SsrfProtectionException;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for LinkFetchService covering validation, SSRF protection, and text extraction.
 */
@DisplayName("Link Fetch Service Tests")
class LinkFetchServiceTest {

    private LinkFetchConfig config;
    private LinkFetchService linkFetchService;

    @BeforeEach
    void setUp() {
        config = new LinkFetchConfig();
        config.setConnectTimeoutMs(3000);
        config.setReadTimeoutMs(10_000);
        config.setMaxContentSizeBytes(5_242_880L);
        config.setMaxRedirects(5);
        config.setUserAgent("QuizMaker-Bot/1.0");
        linkFetchService = new LinkFetchService(config);
    }

    @Nested
    @DisplayName("URL Validation Tests")
    class UrlValidationTests {

        @Test
        @DisplayName("fetchAndExtractText: when URL is null then throws SsrfProtectionException")
        void fetchAndExtractText_withNullUrl_throwsSsrfProtectionException() {
            // Given
            String url = null;

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("URL cannot be null or empty");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is blank then throws SsrfProtectionException")
        void fetchAndExtractText_withBlankUrl_throwsSsrfProtectionException() {
            // Given
            String url = "   ";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("URL cannot be null or empty");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL exceeds max length then throws SsrfProtectionException")
        void fetchAndExtractText_withTooLongUrl_throwsSsrfProtectionException() {
            // Given
            String url = "https://example.com/" + "a".repeat(2100);

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("URL exceeds maximum length");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has invalid format then throws SsrfProtectionException")
        void fetchAndExtractText_withInvalidFormat_throwsSsrfProtectionException() {
            // Given
            String url = "not a url at all";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Invalid URL format");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has file scheme then throws SsrfProtectionException")
        void fetchAndExtractText_withFileScheme_throwsSsrfProtectionException() {
            // Given
            String url = "file:///etc/passwd";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Only HTTP and HTTPS schemes are allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has ftp scheme then throws SsrfProtectionException")
        void fetchAndExtractText_withFtpScheme_throwsSsrfProtectionException() {
            // Given
            String url = "ftp://example.com/file";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Only HTTP and HTTPS schemes are allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has custom port then throws SsrfProtectionException")
        void fetchAndExtractText_withCustomPort_throwsSsrfProtectionException() {
            // Given
            String url = "http://example.com:8080/path";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Only ports 80 and 443 are allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL contains credentials then throws SsrfProtectionException")
        void fetchAndExtractText_withUserInfo_throwsSsrfProtectionException() {
            // Given
            String url = "https://user:password@example.com/resource";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("must not contain embedded credentials");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has no host then throws SsrfProtectionException")
        void fetchAndExtractText_withNoHost_throwsSsrfProtectionException() {
            // Given
            String url = "http:///path";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("URL must have a valid host");
        }
    }

    @Nested
    @DisplayName("SSRF Protection Tests")
    class SsrfProtectionTests {

        @Test
        @DisplayName("fetchAndExtractText: when URL is localhost then throws SsrfProtectionException")
        void fetchAndExtractText_withLocalhost_throwsSsrfProtectionException() {
            // Given
            String url = "http://localhost/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Localhost addresses are not allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is 127.0.0.1 then throws SsrfProtectionException")
        void fetchAndExtractText_with127001_throwsSsrfProtectionException() {
            // Given
            String url = "http://127.0.0.1/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Localhost addresses are not allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is ::1 then throws SsrfProtectionException")
        void fetchAndExtractText_withIpv6Loopback_throwsSsrfProtectionException() {
            // Given
            String url = "http://[::1]/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Localhost addresses are not allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is wildcard address then throws SsrfProtectionException")
        void fetchAndExtractText_withWildcard_throwsSsrfProtectionException() {
            // Given
            String url = "http://0.0.0.0/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Wildcard addresses are not allowed");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is 10.x.x.x range then throws SsrfProtectionException")
        void fetchAndExtractText_with10Range_throwsSsrfProtectionException() {
            // Given
            String url = "http://10.0.0.1/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Private IP");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is 192.168.x.x range then throws SsrfProtectionException")
        void fetchAndExtractText_with192168Range_throwsSsrfProtectionException() {
            // Given
            String url = "http://192.168.1.1/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Private IP");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is 172.16-31.x.x range then throws SsrfProtectionException")
        void fetchAndExtractText_with172Range_throwsSsrfProtectionException() {
            // Given
            String url = "http://172.16.0.1/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Private IP");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is 169.254.x.x link-local then throws SsrfProtectionException")
        void fetchAndExtractText_withLinkLocal_throwsSsrfProtectionException() {
            // Given
            String url = "http://169.254.169.254/api";

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("Link-local addresses are not allowed");
        }
    }

    @Nested
    @DisplayName("Valid URL Tests")
    class ValidUrlTests {

        @Test
        @DisplayName("fetchAndExtractText: when public HTTP URL then validates and fetches successfully")
        void fetchAndExtractText_withPublicHttpUrl_validatesSuccessfully() {
            // Given
            // Note: example.com is a valid, reachable domain that returns actual HTML
            String url = "http://example.com";

            // When
            ConversionResult result = linkFetchService.fetchAndExtractText(url);

            // Then
            // Validates that SSRF checks pass and content is extracted
            assertThat(result).isNotNull();
            assertThat(result.text()).isNotEmpty();
        }

        @Test
        @DisplayName("fetchAndExtractText: when public HTTPS URL then validates and fetches successfully")
        void fetchAndExtractText_withPublicHttpsUrl_validatesSuccessfully() {
            // Given
            // Note: example.com is a valid, reachable domain that returns actual HTML
            String url = "https://example.com";

            // When
            ConversionResult result = linkFetchService.fetchAndExtractText(url);

            // Then
            // Validates that SSRF checks pass and content is extracted
            assertThat(result).isNotNull();
            assertThat(result.text()).isNotEmpty();
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL with path and query then validates successfully")
        void fetchAndExtractText_withPathAndQuery_validatesSuccessfully() {
            // Given
            String url = "https://example.com/path/to/page?query=value&other=123";

            // When / Then
            // URL validation passes, but actual connection fails (expected in unit test)
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(LinkFetchException.class);
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL with default port 80 then validates successfully")
        void fetchAndExtractText_withDefaultPort80_validatesSuccessfully() {
            // Given
            String url = "http://example.com:80/path";

            // When / Then
            // URL validation passes, but actual connection fails (expected in unit test)
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(LinkFetchException.class);
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL with default port 443 then validates successfully")
        void fetchAndExtractText_withDefaultPort443_validatesSuccessfully() {
            // Given
            String url = "https://example.com:443/path";

            // When / Then
            // URL validation passes, but actual connection fails (expected in unit test)
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(LinkFetchException.class);
        }
    }

    @Nested
    @DisplayName("Edge Cases and Boundary Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("fetchAndExtractText: when URL exactly 2048 chars then validates successfully")
        void fetchAndExtractText_withExactly2048Chars_validatesSuccessfully() {
            // Given
            String baseUrl = "https://example.com/";
            String path = "p".repeat(2048 - baseUrl.length());
            String url = baseUrl + path;

            assertThat(url.length()).isEqualTo(2048);

            // When / Then
            // URL validation passes, but actual connection fails (expected in unit test)
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(LinkFetchException.class);
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL is 2049 chars then throws SsrfProtectionException")
        void fetchAndExtractText_with2049Chars_throwsSsrfProtectionException() {
            // Given
            String baseUrl = "https://example.com/";
            String path = "p".repeat(2049 - baseUrl.length());
            String url = baseUrl + path;

            assertThat(url.length()).isEqualTo(2049);

            // When / Then
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(SsrfProtectionException.class)
                    .hasMessageContaining("URL exceeds maximum length");
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has uppercase scheme then normalizes correctly")
        void fetchAndExtractText_withUppercaseScheme_normalizesCorrectly() {
            // Given
            String url = "HTTPS://EXAMPLE.COM/path";

            // When / Then
            // URL validation passes, but actual connection fails (expected in unit test)
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(LinkFetchException.class);
        }

        @Test
        @DisplayName("fetchAndExtractText: when URL has mixed case scheme then normalizes correctly")
        void fetchAndExtractText_withMixedCaseScheme_normalizesCorrectly() {
            // Given
            String url = "HtTpS://example.com/path";

            // When / Then
            // URL validation passes, but actual connection fails (expected in unit test)
            assertThatThrownBy(() -> linkFetchService.fetchAndExtractText(url))
                    .isInstanceOf(LinkFetchException.class);
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("LinkFetchService: when created with config then config is injected")
        void linkFetchService_withConfig_configIsInjected() {
            // Given / When
            // Service is created with mocked config in setUp()

            // Then
            // Just verify the service was created successfully
            assertThat(linkFetchService).isNotNull();
            
            // Config will be used when methods are actually called
            // (no need to verify interactions here as it's checked by other tests)
        }
    }
}
