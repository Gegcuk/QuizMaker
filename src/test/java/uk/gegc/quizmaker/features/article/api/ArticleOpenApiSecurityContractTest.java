package uk.gegc.quizmaker.features.article.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.infra.security.*;
import uk.gegc.quizmaker.features.media.api.dto.PublicImageRenditionDto;
import uk.gegc.quizmaker.shared.api.docs.ApiDiscoveryController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocumentationService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ArticleController.class, ApiDiscoveryController.class})
@Import({
        OpenApiConfig.class,
        SecurityConfig.class,
        PermissionAspect.class,
        ApiDocumentationService.class,
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        ArticleOpenApiSecurityContractTest.SpringDocTestConfig.class
})
class ArticleOpenApiSecurityContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ArticleService articleService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @Autowired
    private List<GroupedOpenApi> groups;

    @MockitoBean private AuthSessionService authSessionService;
    @MockitoBean private AuthSessionMetricsService authSessionMetricsService;
    @MockitoBean(name = "corsConfigurationSource") private CorsConfigurationSource corsConfigurationSource;
    @MockitoBean private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean private OAuth2AuthenticationSuccessHandler successHandler;
    @MockitoBean private OAuth2AuthenticationFailureHandler failureHandler;
    @MockitoBean private OAuth2LoginAuthorizationRequestResolver authorizationRequestResolver;
    @MockitoBean private OAuth2AuthorizationRequestContextRepository authorizationRequestRepository;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean private AppPermissionEvaluator permissionEvaluator;

    @Test
    @WithMockUser
    @DisplayName("full and Articles OpenAPI specs distinguish anonymous public reads from bearer-protected operations")
    void articleOpenApiSecurityContract() throws Exception {
        assertArticleSecurity(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertArticleSecurity(mockMvc.perform(get("/v3/api-docs/articles"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void publicImageContractIsTypedAndDiscoverable() throws Exception {
        JsonNode spec = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/articles"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode schemas = spec.path("components").path("schemas");
        assertThat(spec.at("/paths/~1api~1v1~1articles~1public/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ArticlePageResponse");
        assertThat(schemas.at("/ArticlePageResponse/properties/content/items/$ref").asText())
                .isEqualTo("#/components/schemas/ArticleListItemDto");
        assertThat(spec.at("/paths/~1api~1v1~1articles~1public~1slug~1{slug}/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ArticleDto");
        JsonNode batchSchema = spec.at("/paths/~1api~1v1~1articles~1batch/post/responses/200/content/application~1json/schema");
        assertThat(batchSchema.path("type").asText()).isEqualTo("array");
        assertThat(batchSchema.at("/items/$ref").asText()).isEqualTo("#/components/schemas/ArticleDto");
        for (String dto : List.of("ArticleDto", "ArticleListItemDto", "ArticleUpsertRequest")) {
            assertThat(schemas.at("/" + dto + "/properties/heroImage/$ref").asText())
                    .isEqualTo("#/components/schemas/ArticleImageDto");
        }
        JsonNode rendition = schemas.at("/ArticleImageDto/properties/rendition");
        assertThat(rendition.path("readOnly").asBoolean()).isTrue();
        // OpenAPI may wrap a referenced schema to attach sibling readOnly/nullable metadata.
        assertThat(rendition.toString()).contains("#/components/schemas/PublicImageRenditionDto");
        JsonNode imageSchema = schemas.path("PublicImageRenditionDto");
        assertThat(imageSchema.at("/properties/url/type").asText()).isEqualTo("string");
        assertThat(imageSchema.path("type")).contains(objectMapper.getNodeFactory().textNode("object"),
                objectMapper.getNodeFactory().textNode("null"));
        for (String dimension : List.of("width", "height")) {
            assertThat(imageSchema.at("/properties/" + dimension + "/type"))
                    .contains(objectMapper.getNodeFactory().textNode("integer"), objectMapper.getNodeFactory().textNode("null"));
        }
        assertThat(imageSchema.at("/properties/mimeType/type").asText()).isEqualTo("string");
        JsonNode example = imageSchema.path("example");
        assertThat(example.path("url").asText()).startsWith("https://");
        assertThat(example.path("width").asInt()).isPositive();
        assertThat(example.path("height").asInt()).isPositive();
        assertThat(example.path("mimeType").asText()).startsWith("image/");
        assertThat(objectMapper.treeToValue(example, PublicImageRenditionDto.class).width()).isPositive();
        assertThat(spec.at("/paths/~1api~1v1~1articles~1public~1slug~1{slug}/get/responses/404/content/application~1problem+json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ProblemDetail");
        assertThat(spec.at("/paths/~1api~1v1~1articles~1public/get/responses/429/content/application~1problem+json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ProblemDetail");
        AntPathMatcher matcher = new AntPathMatcher();
        spec.path("paths").fieldNames().forEachRemaining(path -> assertThat(groups.stream()
                .filter(group -> group.getPathsToMatch() != null && group.getPathsToMatch().stream()
                        .anyMatch(pattern -> matcher.match(pattern, path)))
                .map(GroupedOpenApi::getGroup)).containsExactly("articles"));
        mockMvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[?(@.group == 'articles')].specUrl")
                        .value(org.hamcrest.Matchers.contains("/v3/api-docs/articles")));
    }

    @Test
    void anonymousDetailAndListExposeRenditionWithoutChangingPageEnvelope() throws Exception {
        PublicImageRenditionDto rendition = new PublicImageRenditionDto(
                "https://cdn.example.com/media/hero.webp", 1280, 720, "image/webp");
        ArticleDto article = article(rendition);
        when(articleService.getArticleBySlug("public-article", false)).thenReturn(article);
        ArticleListItemDto item = new ArticleListItemDto(article.id(), article.slug(), article.title(),
                article.description(), article.excerpt(), article.heroKicker(), article.heroImage(), article.tags(),
                article.author(), article.readingTime(), article.publishedAt(), article.updatedAt(), article.status(),
                article.contentGroup(), article.canonicalUrl(), article.ogImage(), article.noindex(),
                article.primaryCta(), article.secondaryCta(), article.revision());
        when(articleService.searchArticles(any(), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));
        mockMvc.perform(get("/api/v1/articles/public/slug/public-article").param("includeDrafts", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heroImage.rendition.url").value(rendition.url()))
                .andExpect(jsonPath("$.heroImage.rendition.width").value(1280))
                .andExpect(jsonPath("$.heroImage.rendition.height").value(720))
                .andExpect(jsonPath("$.heroImage.rendition.mimeType").value("image/webp"))
                .andExpect(jsonPath("$.ogImage").value("https://www.example.com/explicit-og.png"));
        verify(articleService).getArticleBySlug("public-article", false);
        mockMvc.perform(get("/api/v1/articles/public").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].heroImage.rendition.url").value(rendition.url()))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageable.pageSize").value(20));
        verify(articleService).searchArticles(eq(new ArticleSearchCriteria(ArticleStatus.PUBLISHED, null, null)), any());
    }

    @Test
    void unavailableRenditionKeepsArticleReadableAndUnpublishedArticleIsNotFound() throws Exception {
        when(articleService.getArticleBySlug("public-article", false)).thenReturn(article(null));
        when(articleService.getArticleBySlug("draft-article", false))
                .thenThrow(new ResourceNotFoundException("Article not found"));
        mockMvc.perform(get("/api/v1/articles/public/slug/public-article"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.heroImage.assetId").exists())
                .andExpect(jsonPath("$.heroImage.rendition").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.heroImage.alt").value("Article illustration"));
        mockMvc.perform(get("/api/v1/articles/public/slug/draft-article"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void authoringInputCannotOverrideDerivedRendition() throws Exception {
        ArticleDto article = article(new PublicImageRenditionDto("https://cdn.example.com/hero.webp", 1280, 720, "image/webp"));
        ArticleUpsertRequest input = objectMapper.readValue(objectMapper.writeValueAsString(article), ArticleUpsertRequest.class);
        assertThat(input.heroImage().assetId()).isEqualTo(article.heroImage().assetId());
        assertThat(input.heroImage().alt()).isEqualTo(article.heroImage().alt());
        assertThat(input.heroImage().caption()).isEqualTo(article.heroImage().caption());
        assertThat(input.heroImage().rendition()).isNull();
        assertThat(input.ogImage()).isEqualTo(article.ogImage());
        ArticleImageDto legacy = objectMapper.readValue("{\"assetId\":\"8b5b6c1a-55aa-4c22-9911-112233445566\",\"alt\":\"Legacy alt\",\"caption\":null}", ArticleImageDto.class);
        assertThat(legacy.rendition()).isNull();
        assertThat(legacy.alt()).isEqualTo("Legacy alt");
    }

    @Test
    void protectedArticleStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/articles")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(articleService);
    }

    @Test
    @WithMockUser
    void protectedArticleStillRequiresPermission() throws Exception {
        mockMvc.perform(get("/api/v1/articles")).andExpect(status().isForbidden());
        verifyNoInteractions(articleService);
    }

    private ArticleDto article(PublicImageRenditionDto rendition) {
        ArticleImageDto hero = new ArticleImageDto(UUID.fromString("8b5b6c1a-55aa-4c22-9911-112233445566"),
                "Article illustration", "Caption", rendition);
        return new ArticleDto(UUID.fromString("97fd7946-5c6b-4697-a3d1-d14f2bd96540"), "public-article", "Title",
                "Description", "Excerpt", null, hero, List.of(), new ArticleAuthorDto("Author", "Editor"),
                "5 minutes", Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-02T10:00:00Z"),
                ArticleStatus.PUBLISHED, null, "https://www.example.com/explicit-og.png", false,
                ArticleContentType.BLOG, new ArticleCallToActionDto("Start", "/", null),
                new ArticleCallToActionDto("Read", "/blog", null), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), 1);
    }

    private void assertArticleSecurity(String specificationJson) throws Exception {
        JsonNode specification = objectMapper.readTree(specificationJson);

        assertAnonymous(specification, "/paths/~1api~1v1~1articles~1public/get/security");
        assertAnonymous(specification, "/paths/~1api~1v1~1articles~1public~1slug~1{slug}/get/security");
        assertBearerAuth(specification, "/paths/~1api~1v1~1articles/get/security");
    }

    private void assertAnonymous(JsonNode specification, String securityPointer) {
        JsonNode security = specification.at(securityPointer);
        assertThat(security.isArray()).isTrue();
        assertThat(security.size()).isEqualTo(1);
        assertThat(security.get(0).isObject()).isTrue();
        assertThat(security.get(0).isEmpty()).isTrue();
    }

    private void assertBearerAuth(JsonNode specification, String securityPointer) {
        JsonNode security = specification.at(securityPointer);
        assertThat(security.isArray()).isTrue();
        assertThat(security.size()).isEqualTo(1);
        assertThat(security.get(0).path(OpenApiConfig.BEARER_AUTH_SCHEME).isArray()).isTrue();
    }
}
