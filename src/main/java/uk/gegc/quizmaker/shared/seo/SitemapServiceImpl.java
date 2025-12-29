package uk.gegc.quizmaker.shared.seo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.article.api.dto.SitemapEntryDto;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SitemapServiceImpl implements SitemapService {

    private static final List<StaticEntry> DEFAULT_STATIC_ENTRIES = List.of(
            new StaticEntry("/", "weekly", 1.0),
            new StaticEntry("/blog/", "weekly", 0.8),
            new StaticEntry("/terms/", "monthly", 0.4),
            new StaticEntry("/privacy/", "monthly", 0.4),
            new StaticEntry("/theme-demo/", "monthly", 0.3)
    );

    private final ArticleService articleService;
    private final String baseUrl;

    public SitemapServiceImpl(
            ArticleService articleService,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl
    ) {
        this.articleService = articleService;
        this.baseUrl = normalizeBaseUrl(frontendBaseUrl);
    }

    @Override
    public String getSitemapXml() {
        Map<String, UrlEntry> entries = new LinkedHashMap<>();
        for (StaticEntry entry : DEFAULT_STATIC_ENTRIES) {
            String loc = toAbsoluteUrl(entry.path());
            entries.put(loc, new UrlEntry(loc, null, entry.changefreq(), entry.priority()));
        }
        for (SitemapEntryDto entry : articleService.getSitemapEntries(ArticleStatus.PUBLISHED)) {
            String loc = toAbsoluteUrl(entry.url());
            entries.putIfAbsent(loc, new UrlEntry(loc, entry.updatedAt(), entry.changefreq(), entry.priority()));
        }
        return buildUrlSetXml(new ArrayList<>(entries.values()));
    }

    @Override
    public String getArticleSitemapXml() {
        List<UrlEntry> entries = articleService.getSitemapEntries(ArticleStatus.PUBLISHED).stream()
                .map(entry -> new UrlEntry(
                        toAbsoluteUrl(entry.url()),
                        entry.updatedAt(),
                        entry.changefreq(),
                        entry.priority()
                ))
                .toList();
        return buildUrlSetXml(entries);
    }

    @Override
    public String getRobotsTxt() {
        StringBuilder builder = new StringBuilder();
        builder.append("User-agent: *\n");
        builder.append("Allow: /\n");
        builder.append("Disallow: /api/\n");
        builder.append("Disallow: /swagger-ui/\n");
        builder.append("Disallow: /v3/api-docs/\n");
        builder.append("Sitemap: ").append(baseUrl).append("/sitemap.xml\n");
        return builder.toString();
    }

    private String buildUrlSetXml(List<UrlEntry> entries) {
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        builder.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (UrlEntry entry : entries) {
            builder.append("  <url>\n");
            appendTag(builder, "loc", entry.loc());
            if (entry.lastmod() != null) {
                appendTag(builder, "lastmod", DateTimeFormatter.ISO_INSTANT.format(entry.lastmod()));
            }
            if (StringUtils.hasText(entry.changefreq())) {
                appendTag(builder, "changefreq", entry.changefreq());
            }
            if (entry.priority() != null) {
                appendTag(builder, "priority", formatPriority(entry.priority()));
            }
            builder.append("  </url>\n");
        }
        builder.append("</urlset>\n");
        return builder.toString();
    }

    private void appendTag(StringBuilder builder, String name, String value) {
        builder.append("    <").append(name).append(">")
                .append(escapeXml(value))
                .append("</").append(name).append(">\n");
    }

    private String toAbsoluteUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return baseUrl;
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return baseUrl + trimmed;
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        String candidate = StringUtils.hasText(rawBaseUrl) ? rawBaseUrl.trim() : "http://localhost:3000";
        if (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private String formatPriority(Double priority) {
        return String.format(Locale.US, "%.1f", priority);
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value;
        escaped = escaped.replace("&", "&amp;");
        escaped = escaped.replace("<", "&lt;");
        escaped = escaped.replace(">", "&gt;");
        escaped = escaped.replace("\"", "&quot;");
        escaped = escaped.replace("'", "&apos;");
        return escaped;
    }

    private record StaticEntry(String path, String changefreq, Double priority) {
    }

    private record UrlEntry(String loc, Instant lastmod, String changefreq, Double priority) {
    }
}
