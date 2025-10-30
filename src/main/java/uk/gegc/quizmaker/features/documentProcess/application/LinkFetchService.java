package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.documentProcess.config.LinkFetchConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.ContentSizeLimitException;
import uk.gegc.quizmaker.features.documentProcess.domain.LinkFetchException;
import uk.gegc.quizmaker.features.documentProcess.domain.SsrfProtectionException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Service for safely fetching web content with SSRF protection.
 * Implements security controls to prevent Server-Side Request Forgery attacks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LinkFetchService {

    private final LinkFetchConfig config;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443, -1); // -1 means default port

    /**
     * Fetches and extracts text from a URL with SSRF protection.
     *
     * @param url the URL to fetch (must be http or https)
     * @return ConversionResult containing extracted text
     */
    public ConversionResult fetchAndExtractText(String url) {
        log.info("Fetching link: {}", url);

        URI initialUri = parseAndValidateUrl(url);
        String html = fetchHtml(initialUri);
        String text = extractText(html);

        log.info("Successfully fetched and extracted text from link: {} ({} characters)", url, text.length());
        return new ConversionResult(text);
    }

    private URI parseAndValidateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new SsrfProtectionException("URL cannot be null or empty");
        }
        if (url.length() > 2048) {
            throw new SsrfProtectionException("URL exceeds maximum length of 2048 characters");
        }
        try {
            URI uri = new URI(url).normalize();
            return validateUrl(uri);
        } catch (SsrfProtectionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SsrfProtectionException("Invalid URL format: " + ex.getMessage(), ex);
        }
    }

    private URI validateUrl(URI uri) {
        if (!uri.isAbsolute()) {
            throw new SsrfProtectionException("URL must be absolute");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new SsrfProtectionException("Only HTTP and HTTPS schemes are allowed");
        }

        if (uri.getUserInfo() != null) {
            throw new SsrfProtectionException("URL must not contain embedded credentials");
        }

        int port = uri.getPort();
        if (port != -1 && !ALLOWED_PORTS.contains(port)) {
            throw new SsrfProtectionException("Only ports 80 and 443 are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfProtectionException("URL must have a valid host");
        }

        validateHostResolvesToSafeIps(host);
        return uri;
    }

    private void validateHostResolvesToSafeIps(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                validateIpAddress(address, host);
            }
        } catch (UnknownHostException ex) {
            throw new SsrfProtectionException("Cannot resolve host: " + host, ex);
        }
    }

    private String fetchHtml(URI startingUri) {
        HttpURLConnection connection = null;
        URI currentUri = startingUri;
        int redirectCount = 0;

        try {
            connection = openConnection(currentUri);
            int responseCode = connection.getResponseCode();

            while (isRedirect(responseCode)) {
                redirectCount++;
                if (redirectCount > config.getMaxRedirects()) {
                    throw new LinkFetchException("Too many redirects (max: " + config.getMaxRedirects() + ")");
                }

                String location = connection.getHeaderField("Location");
                if (location == null || location.isBlank()) {
                    throw new LinkFetchException("Redirect without Location header");
                }

                URI redirectUri = resolveRedirect(currentUri, location);
                redirectUri = validateUrl(redirectUri);

                connection.disconnect();
                currentUri = redirectUri;
                connection = openConnection(currentUri);
                responseCode = connection.getResponseCode();
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new LinkFetchException("HTTP error code: " + responseCode);
            }

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("html")) {
                log.warn("Non-HTML content type: {}", contentType);
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > config.getMaxContentSizeBytes()) {
                throw new ContentSizeLimitException(
                    String.format("Content size exceeds limit of %d bytes", config.getMaxContentSizeBytes())
                );
            }

            try (InputStream inputStream = connection.getInputStream()) {
                return readWithSizeLimit(inputStream);
            }
        } catch (IOException ex) {
            throw new LinkFetchException("Failed to fetch URL: " + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(URI targetUri) throws IOException {
        URL url = targetUri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(config.getConnectTimeoutMs());
        connection.setReadTimeout(config.getReadTimeoutMs());
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", config.getUserAgent());
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        connection.connect();

        // Validate the resolved host after establishing the connection to reduce DNS rebinding window.
        validateHostResolvesToSafeIps(connection.getURL().getHost());
        return connection;
    }

    private boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
            || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
            || responseCode == HttpURLConnection.HTTP_SEE_OTHER
            || responseCode == 307
            || responseCode == 308;
    }

    private URI resolveRedirect(URI baseUri, String locationHeader) {
        try {
            URI locationUri = new URI(locationHeader.trim());
            if (!locationUri.isAbsolute()) {
                locationUri = baseUri.resolve(locationUri);
            }
            return locationUri.normalize();
        } catch (Exception ex) {
            throw new LinkFetchException("Invalid redirect URL: " + locationHeader, ex);
        }
    }

    private String readWithSizeLimit(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        long totalBytes = 0;

        while ((bytesRead = inputStream.read(chunk)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > config.getMaxContentSizeBytes()) {
                throw new ContentSizeLimitException(
                    String.format("Content size exceeds limit of %d bytes", config.getMaxContentSizeBytes())
                );
            }
            buffer.write(chunk, 0, bytesRead);
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void validateIpAddress(InetAddress address, String host) {
        byte[] bytes = address.getAddress();

        if (address.isLoopbackAddress()) {
            throw new SsrfProtectionException("Localhost addresses are not allowed: " + host);
        }
        if (address.isLinkLocalAddress()) {
            throw new SsrfProtectionException("Link-local addresses are not allowed: " + host);
        }
        if (address.isSiteLocalAddress()) {
            throw new SsrfProtectionException("Private IP addresses are not allowed: " + host);
        }
        if (address.isAnyLocalAddress()) {
            throw new SsrfProtectionException("Wildcard addresses are not allowed: " + host);
        }
        if (address.isMulticastAddress()) {
            throw new SsrfProtectionException("Multicast addresses are not allowed: " + host);
        }

        if (bytes.length == 4) {
            int firstOctet = bytes[0] & 0xFF;
            int secondOctet = bytes[1] & 0xFF;

            if (firstOctet == 10) {
                throw new SsrfProtectionException("Private IP range 10.x.x.x is not allowed: " + host);
            }
            if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
                throw new SsrfProtectionException("Private IP range 172.16-31.x.x is not allowed: " + host);
            }
            if (firstOctet == 192 && secondOctet == 168) {
                throw new SsrfProtectionException("Private IP range 192.168.x.x is not allowed: " + host);
            }
        } else if (bytes.length == 16) {
            int firstByte = bytes[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                throw new SsrfProtectionException("Private IPv6 addresses are not allowed: " + host);
            }
        }
    }

    private String extractText(String html) {
        try {
            Document doc = Jsoup.parse(html);
            doc.select("script,style,noscript,template,svg,canvas").remove();

            String text = doc.text();
            if (text.isBlank()) {
                throw new LinkFetchException("No text content extracted from HTML");
            }
            return text;
        } catch (Exception ex) {
            throw new LinkFetchException("Failed to extract text from HTML: " + ex.getMessage(), ex);
        }
    }
}
