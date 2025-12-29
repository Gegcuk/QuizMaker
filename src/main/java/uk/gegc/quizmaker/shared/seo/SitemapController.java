package uk.gegc.quizmaker.shared.seo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "SEO", description = "Sitemap and robots endpoints")
public class SitemapController {

    private final SitemapService sitemapService;

    public SitemapController(SitemapService sitemapService) {
        this.sitemapService = sitemapService;
    }

    @Operation(summary = "Get XML sitemap", description = "Returns the XML sitemap for static pages and published articles.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sitemap XML",
                    content = @Content(mediaType = "application/xml", schema = @Schema(implementation = String.class)))
    })
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemapXml() {
        return sitemapService.getSitemapXml();
    }

    @Operation(summary = "Get XML article sitemap", description = "Returns the XML sitemap for published articles only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article sitemap XML",
                    content = @Content(mediaType = "application/xml", schema = @Schema(implementation = String.class)))
    })
    @GetMapping(value = "/sitemap_articles.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemapArticlesXml() {
        return sitemapService.getArticleSitemapXml();
    }

    @Operation(summary = "Get robots.txt", description = "Returns robots directives including sitemap location.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Robots.txt content",
                    content = @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class)))
    })
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robotsTxt() {
        return sitemapService.getRobotsTxt();
    }
}
