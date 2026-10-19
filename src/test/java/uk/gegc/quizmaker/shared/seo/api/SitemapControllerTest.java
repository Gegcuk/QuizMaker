package uk.gegc.quizmaker.shared.seo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import uk.gegc.quizmaker.features.article.api.dto.SitemapEntryDto;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthorizationRequestContextRepository;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2LoginAuthorizationRequestResolver;
import uk.gegc.quizmaker.shared.api.docs.ApiDiscoveryController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocumentationService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.shared.seo.config.SeoProperties;
import uk.gegc.quizmaker.shared.seo.service.SitemapServiceImpl;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {SitemapController.class, ApiDiscoveryController.class})
@Import({
        SitemapServiceImpl.class,
        SecurityConfig.class,
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        ApiDocumentationService.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        SitemapControllerTest.SitemapTestConfig.class
})
@TestPropertySource(locations = "classpath:seo.properties",
        properties = "app.frontend.base-url=https://www.quizzence.com")
@DisplayName("Public sitemap configuration and HTTP contract")
class SitemapControllerTest {

    private static final String BASE_URL = "https://www.quizzence.com";
    // Indexable static routes agreed in #802; dynamic /blog/:slug/ belongs to the article sitemap.
    private static final List<String> INDEXABLE_STATIC_PATHS = List.of(
            "/", "/blog/", "/blog/retrieval-practice-template/", "/terms/",
            "/privacy/", "/faq/", "/values/", "/roadmap/"
    );

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SeoProperties.class, SpringDocConfigProperties.class})
    static class SitemapTestConfig {
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private List<GroupedOpenApi> groups;

    @MockitoBean private ArticleService articleService;
    @MockitoBean private AuthSessionService authSessionService;
    @MockitoBean private AuthSessionMetricsService authSessionMetricsService;
    @MockitoBean(name = "corsConfigurationSource") private CorsConfigurationSource corsConfigurationSource;
    @MockitoBean private TrustedProxyUtil trustedProxyUtil;
    @MockitoBean private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean private OAuth2AuthenticationSuccessHandler successHandler;
    @MockitoBean private OAuth2AuthenticationFailureHandler failureHandler;
    @MockitoBean private OAuth2LoginAuthorizationRequestResolver authorizationRequestResolver;
    @MockitoBean private OAuth2AuthorizationRequestContextRepository authorizationRequestRepository;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    @DisplayName("Static sitemap contains exactly the indexable canonical routes from the shipped configuration")
    void sitemapXml_containsOnlyIndexableStaticRoutes() throws Exception {
        String xml = getXml("/sitemap.xml");

        assertThat(locations(xml)).containsExactlyInAnyOrderElementsOf(
                INDEXABLE_STATIC_PATHS.stream().map(path -> BASE_URL + path).toList());
        verifyNoInteractions(articleService);
    }

    @Test
    @DisplayName("Published article URLs stay in the article sitemap and never enter the static sitemap")
    void articleSitemap_keepsPublishedArticlesSeparate() throws Exception {
        List<String> articlePaths = List.of("/blog/first-published-article/", "/blog/second-published-article/");
        when(articleService.getSitemapEntries(ArticleStatus.PUBLISHED)).thenReturn(articlePaths.stream()
                .map(path -> new SitemapEntryDto(path, Instant.parse("2026-08-24T12:00:00Z"), "weekly", 0.8))
                .toList());

        List<String> articleLocations = locations(getXml("/sitemap_articles.xml"));
        List<String> staticLocations = locations(getXml("/sitemap.xml"));

        assertThat(articleLocations).containsExactlyElementsOf(
                articlePaths.stream().map(path -> BASE_URL + path).toList());
        assertThat(staticLocations).doesNotContainAnyElementsOf(articleLocations);
        assertThat(articleLocations).doesNotContainAnyElementsOf(staticLocations);
        verify(articleService).getSitemapEntries(ArticleStatus.PUBLISHED);
    }

    @Test
    @DisplayName("An empty article catalogue still returns a valid empty XML sitemap")
    void articleSitemap_whenNoPublishedArticles_returnsEmptyUrlset() throws Exception {
        when(articleService.getSitemapEntries(ArticleStatus.PUBLISHED)).thenReturn(List.of());

        assertThat(locations(getXml("/sitemap_articles.xml"))).isEmpty();
    }

    @Test
    @DisplayName("Public robots.txt advertises both distinct sitemap locations")
    void robotsTxt_advertisesBothSitemaps() throws Exception {
        String robots = mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn().getResponse().getContentAsString();

        assertThat(robots.lines().filter(line -> line.startsWith("Sitemap:")).toList())
                .containsExactly("Sitemap: " + BASE_URL + "/sitemap.xml",
                        "Sitemap: " + BASE_URL + "/sitemap_articles.xml");
        verifyNoInteractions(articleService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/seo"})
    @DisplayName("Full and SEO OpenAPI retain public XML/plain-text response contracts")
    void openApi_documentsPublicResponseContracts(String specUrl) throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get(specUrl))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        for (String path : List.of("/sitemap.xml", "/sitemap_articles.xml", "/robots.txt")) {
            JsonNode operation = specification.path("paths").path(path).path("get");
            assertThat(operation.isMissingNode()).isFalse();
            assertThat(operation.path("security")).isEqualTo(objectMapper.readTree("[{}]"));
            String mediaType = path.endsWith(".xml") ? "application/xml" : "text/plain";
            assertThat(operation.path("responses").path("200").path("content")
                    .path(mediaType).path("schema").path("type").asText()).isEqualTo("string");
        }
    }

    @Test
    @DisplayName("Sitemap routes belong only to the SEO group and that group is discoverable")
    void seoGroup_isUniqueAndDiscoverable() throws Exception {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String path : List.of("/sitemap.xml", "/sitemap_articles.xml", "/robots.txt")) {
            assertThat(groups.stream()
                    .filter(group -> group.getPathsToMatch() != null
                            && group.getPathsToMatch().stream().anyMatch(pattern -> matcher.match(pattern, path)))
                    .map(GroupedOpenApi::getGroup).toList()).containsExactly("seo");
        }

        mockMvc.perform(get("/api/v1/api-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[?(@.group == 'seo')].specUrl")
                        .value(org.hamcrest.Matchers.contains("/v3/api-docs/seo")));
    }

    private String getXml(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andReturn().getResponse().getContentAsString();
    }

    private List<String> locations(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("urlset");
        NodeList nodes = document.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        return IntStream.range(0, nodes.getLength()).mapToObj(index -> nodes.item(index).getTextContent()).toList();
    }
}
