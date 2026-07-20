package uk.gegc.quizmaker.features.bugreport.api;

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
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.config.BugReportProperties;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {BugReportController.class, BugReportAdminController.class})
@Import({
        OpenApiGroupConfig.class,
        OpenApiConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        PermissionAspect.class,
        BugReportOpenApiContractTest.SpringDocTestConfig.class
})
@EnableAspectJAutoProxy
class BugReportOpenApiContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BugReportService bugReportService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private BugReportProperties bugReportProperties;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @Test
    @WithMockUser
    @DisplayName("GET /v3/api-docs/bug-reports publishes public and administrative typed contracts")
    void bugReportOpenApiContract() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs/bug-reports"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode specification = objectMapper.readTree(body);

        assertThat(specification.path("paths").has("/api/v1/bug-reports")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/admin/bug-reports")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/admin/bug-reports/{id}")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/admin/bug-reports/bulk-delete")).isTrue();

        assertThat(specification.path("security").isArray()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1bug-reports/post/security").isArray()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1bug-reports/post/security").size()).isEqualTo(1);
        assertThat(specification.at("/paths/~1api~1v1~1bug-reports/post/security/0").isObject()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1bug-reports/post/security/0").isEmpty()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1bug-reports/post/responses/201/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/BugReportSubmissionResponse");
        assertThat(specification.at("/paths/~1api~1v1~1bug-reports/post/responses/429/content/application~1problem+json/examples/Rate limit exceeded/value/retryAfterSeconds").asInt())
                .isEqualTo(30);

        assertThat(specification.at("/paths/~1api~1v1~1admin~1bug-reports/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/BugReportPageResponse");
        assertThat(specification.at("/paths/~1api~1v1~1admin~1bug-reports~1{id}/get/responses/404/content/application~1problem+json/examples/Missing report/value/status").asInt())
                .isEqualTo(404);
        assertThat(specification.at("/paths/~1api~1v1~1admin~1bug-reports/get/responses/403").isMissingNode()).isFalse();

        assertThat(specification.path("components").path("schemas").has("BugReportSubmissionResponse")).isTrue();
        assertThat(specification.path("components").path("schemas").has("BugReportPageResponse")).isTrue();
        assertThat(specification.path("components").path("schemas").has("BugReportPageableMetadata")).isTrue();
    }
}
