package uk.gegc.quizmaker.shared.seo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.article.api.dto.SitemapEntryDto;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.shared.seo.config.SeoProperties;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SitemapServiceImpl")
class SitemapServiceImplTest {

    @Mock
    private ArticleService articleService;

    private SitemapServiceImpl sitemapService;

    @BeforeEach
    void setUp() {
        SeoProperties seoProperties = new SeoProperties();
        seoProperties.setStaticEntries(List.of(new SeoProperties.SitemapEntry("/", "weekly", 1.0)));
        seoProperties.setSitemapPaths(List.of("/sitemap.xml"));
        sitemapService = new SitemapServiceImpl(articleService, seoProperties, "https://www.quizzence.com/");
    }

    @Test
    @DisplayName("getSitemapXml includes static entries and article URLs")
    void getSitemapXml_includesStaticAndArticleUrls() {
        SitemapEntryDto entry = new SitemapEntryDto(
                "/blog/sample-slug",
                Instant.parse("2025-01-01T00:00:00Z"),
                "weekly",
                0.8
        );
        when(articleService.getSitemapEntries(ArticleStatus.PUBLISHED)).thenReturn(List.of(entry));

        String xml = sitemapService.getSitemapXml();

        assertThat(xml).contains("<loc>https://www.quizzence.com/</loc>");
        assertThat(xml).contains("<loc>https://www.quizzence.com/blog/sample-slug</loc>");
        assertThat(xml).contains("<lastmod>2025-01-01T00:00:00Z</lastmod>");
    }

    @Test
    @DisplayName("getArticleSitemapXml preserves absolute URLs")
    void getArticleSitemapXml_preservesAbsoluteUrl() {
        SitemapEntryDto entry = new SitemapEntryDto(
                "https://blog.example.com/custom",
                Instant.parse("2025-02-01T00:00:00Z"),
                "weekly",
                0.8
        );
        when(articleService.getSitemapEntries(ArticleStatus.PUBLISHED)).thenReturn(List.of(entry));

        String xml = sitemapService.getArticleSitemapXml();

        assertThat(xml).contains("<loc>https://blog.example.com/custom</loc>");
    }

    @Test
    @DisplayName("getRobotsTxt includes sitemap location")
    void getRobotsTxt_includesSitemap() {
        String robots = sitemapService.getRobotsTxt();

        assertThat(robots).contains("Sitemap: https://www.quizzence.com/sitemap.xml");
    }
}
