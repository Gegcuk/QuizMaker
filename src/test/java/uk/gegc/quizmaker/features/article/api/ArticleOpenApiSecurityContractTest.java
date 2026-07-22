package uk.gegc.quizmaker.features.article.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ArticleController.class)
@Import({
        OpenApiConfig.class,
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
