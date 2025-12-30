package uk.gegc.quizmaker.shared.seo.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.shared.seo.service.SitemapService;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SitemapController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SitemapController")
class SitemapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SitemapService sitemapService;

    @Test
    @DisplayName("GET /sitemap.xml returns XML sitemap")
    void sitemapXml_returnsUrlset() throws Exception {
        when(sitemapService.getSitemapXml()).thenReturn("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url>
                    <loc>http://localhost:3000/blog/sample-slug</loc>
                  </url>
                </urlset>
                """);

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<urlset")))
                .andExpect(content().string(containsString("<loc>http://localhost:3000/blog/sample-slug</loc>")));
    }

    @Test
    @DisplayName("GET /sitemap_articles.xml returns article sitemap")
    void sitemapArticlesXml_returnsUrlset() throws Exception {
        when(sitemapService.getArticleSitemapXml()).thenReturn("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                </urlset>
                """);

        mockMvc.perform(get("/sitemap_articles.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<urlset")));
    }

    @Test
    @DisplayName("GET /robots.txt returns robots content")
    void robotsTxt_returnsContent() throws Exception {
        when(sitemapService.getRobotsTxt()).thenReturn("User-agent: *\nSitemap: http://localhost:3000/sitemap.xml\n");

        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("User-agent: *")))
                .andExpect(content().string(containsString("Sitemap: ")))
                .andExpect(content().string(containsString("/sitemap.xml")));
    }
}
