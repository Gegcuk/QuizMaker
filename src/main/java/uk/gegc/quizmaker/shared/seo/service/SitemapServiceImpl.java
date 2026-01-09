package uk.gegc.quizmaker.shared.seo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.shared.seo.config.SeoProperties;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class SitemapServiceImpl implements SitemapService {

    private final ArticleService articleService;
    private final SeoProperties seoProperties;
    private final String baseUrl;

    public SitemapServiceImpl(
            ArticleService articleService,
            SeoProperties seoProperties,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl
    ) {
        this.articleService = articleService;
        this.seoProperties = seoProperties;
        this.baseUrl = normalizeBaseUrl(frontendBaseUrl);
    }

    @Override
    public String getSitemapXml() {
        // Main sitemap only includes static pages, not individual articles
        // Articles are included in /sitemap_articles.xml via getArticleSitemapXml()
        List<UrlEntry> entries = new ArrayList<>();
        for (SeoProperties.SitemapEntry entry : safeStaticEntries()) {
            String loc = toAbsoluteUrl(entry.getPath());
            entries.add(new UrlEntry(loc, null, entry.getChangefreq(), entry.getPriority()));
        }
        return buildUrlSetXml(entries);
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
        SeoProperties.Robots robots = seoProperties.getRobots();
        String userAgent = robots != null && StringUtils.hasText(robots.getUserAgent())
                ? robots.getUserAgent()
                : "*";
        builder.append("User-agent: ").append(userAgent).append("\n");
        for (String allow : safeList(robots != null ? robots.getAllow() : null)) {
            if (StringUtils.hasText(allow)) {
                builder.append("Allow: ").append(allow).append("\n");
            }
        }
        for (String disallow : safeList(robots != null ? robots.getDisallow() : null)) {
            if (StringUtils.hasText(disallow)) {
                builder.append("Disallow: ").append(disallow).append("\n");
            }
        }
        for (String sitemapPath : safeList(seoProperties.getSitemapPaths())) {
            if (StringUtils.hasText(sitemapPath)) {
                builder.append("Sitemap: ").append(toAbsoluteUrl(sitemapPath)).append("\n");
            }
        }
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

    private List<SeoProperties.SitemapEntry> safeStaticEntries() {
        List<SeoProperties.SitemapEntry> entries = seoProperties.getStaticEntries();
        return entries != null ? entries : List.of();
    }

    private List<String> safeList(List<String> values) {
        return values != null ? values : List.of();
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

    private record UrlEntry(String loc, Instant lastmod, String changefreq, Double priority) {
    }
}
