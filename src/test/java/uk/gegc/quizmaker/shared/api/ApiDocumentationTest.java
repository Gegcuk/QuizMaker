package uk.gegc.quizmaker.shared.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.features.question.api.QuestionController;
import uk.gegc.quizmaker.features.question.application.QuestionSchemaService;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.shared.api.docs.ApiDiscoveryController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocsController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocumentationService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = {
        ApiDiscoveryController.class,
        ApiDocsController.class,
        QuestionController.class
})
@Import({
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        SecurityConfig.class,
        ApiDocumentationService.class,
        QuestionSchemaService.class,
        QuestionSchemaRegistry.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        SwaggerConfig.class,
        ApiDocumentationTest.SpringDocTestConfig.class
})
@DisplayName("Database-free API documentation contracts")
class ApiDocumentationTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            SpringDocConfigProperties.class,
            SwaggerUiConfigProperties.class,
            SwaggerUiOAuthProperties.class
    })
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private QuestionService questionService;

    @MockitoBean
    private AuthSessionService authSessionService;

    @MockitoBean
    private AuthSessionMetricsService authSessionMetricsService;

    @MockitoBean(name = "corsConfigurationSource")
    private CorsConfigurationSource corsConfigurationSource;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    @DisplayName("loads the documentation slice without database infrastructure")
    void documentationSliceHasNoDataSource() {
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
    }

    @Test
    @DisplayName("publishes an actual full SpringDoc specification")
    void fullSpecificationIsGenerated() throws Exception {
        JsonNode specification = getJson("/v3/api-docs");

        assertThat(specification.path("openapi").asText()).startsWith("3.");
        assertThat(specification.path("paths").has("/api/v1/api-summary")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/questions/schemas")).isTrue();
    }

    @Test
    @DisplayName("uses the canonical grouped SpringDoc route")
    void questionsSpecificationUsesGroupedRoute() throws Exception {
        JsonNode specification = getJson("/v3/api-docs/questions");

        assertThat(specification.path("paths").has("/api/v1/questions/schemas")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/questions/schemas/{questionType}")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/api-summary")).isFalse();
    }

    @Test
    @DisplayName("returns cacheable API discovery metadata")
    void apiSummaryIsCacheable() throws Exception {
        String response = mockMvc.perform(get("/api/v1/api-summary"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=900")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode summary = objectMapper.readTree(response);
        assertThat(summary.path("version").asText()).isEqualTo("v1");
        assertThat(summary.path("baseUrl").asText()).isEqualTo("/api/v1");
        assertThat(summary.path("fullSpecUrl").asText()).isEqualTo("/v3/api-docs");
        assertThat(summary.path("fullDocsUrl").asText()).isEqualTo("/swagger-ui/index.html");
        assertThat(summary.path("groups").isArray()).isTrue();
        assertThat(summary.path("groups").findValuesAsText("group")).containsExactlyInAnyOrderElementsOf(Set.of(
                "auth", "quizzes", "questions", "attempts", "documents", "billing",
                "articles", "media", "bug-reports", "seo", "ai", "admin"));
        summary.path("groups").forEach(group -> {
            assertThat(group.path("displayName").asText()).isNotBlank();
            assertThat(group.path("description").asText()).isNotBlank();
            assertThat(group.path("icon").asText()).isNotBlank();
            assertThat(group.path("specUrl").asText()).startsWith("/v3/api-docs/");
            assertThat(group.path("docsUrl").asText()).startsWith("/swagger-ui/index.html");
            assertThat(group.path("estimatedSizeKB").asInt()).isPositive();
        });
    }

    @Test
    @DisplayName("routes the human documentation landing page with its model")
    void documentationLandingPageIsRouted() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(view().name("api-docs-landing"))
                .andExpect(model().attributeExists("summary", "groups"))
                .andExpect(content().string(containsString("QuizMaker API Documentation")))
                .andExpect(content().string(containsString("/swagger-ui/index.html")));
    }

    @Test
    @DisplayName("serves the advertised Swagger UI without authentication")
    void swaggerUiIsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("serves the question schema index without authentication")
    void questionSchemaIndexIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/questions/schemas"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("keeps unrelated API routes protected")
    void protectedApiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/questions"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode getJson(String path) throws Exception {
        String response = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
