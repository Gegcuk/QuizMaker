package uk.gegc.quizmaker.shared.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.article.api.dto.SitemapEntryDto;
import uk.gegc.quizmaker.features.article.application.ArticleService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SitemapController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@DisplayName("SitemapController")
class SitemapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleService articleService;

    @Test
    @DisplayName("GET /sitemap.xml returns XML sitemap")
    void sitemapXml_returnsUrlset() throws Exception {
        SitemapEntryDto entry = new SitemapEntryDto("/blog/sample-slug", Instant.parse("2025-01-01T00:00:00Z"), "weekly", 0.8);
        when(articleService.getSitemapEntries(any())).thenReturn(List.of(entry));

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<urlset")))
                .andExpect(content().string(containsString("<loc>http://localhost:3000/blog/sample-slug</loc>")));
    }

    @Test
    @DisplayName("GET /sitemap_articles.xml returns article sitemap")
    void sitemapArticlesXml_returnsUrlset() throws Exception {
        when(articleService.getSitemapEntries(any())).thenReturn(List.of());

        mockMvc.perform(get("/sitemap_articles.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<urlset")));
    }

    @Test
    @DisplayName("GET /robots.txt returns robots content")
    void robotsTxt_returnsContent() throws Exception {
        when(articleService.getSitemapEntries(any())).thenReturn(List.of());

        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("User-agent: *")))
                .andExpect(content().string(containsString("Sitemap: ")))
                .andExpect(content().string(containsString("/sitemap.xml")));
    }
}
