package uk.gegc.quizmaker.features.auth.api;

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
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        AuthOpenApiContractTest.SpringDocTestConfig.class
})
class AuthOpenApiContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @Test
    @WithMockUser
    @DisplayName("GET /v3/api-docs/auth documents type-restricted refresh and idempotent logout")
    void authOpenApiContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/auth"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(specification.at("/paths/~1api~1v1~1auth~1refresh/post/description").asText())
                .contains("type=refresh", "single-use");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1refresh/post/responses/401");
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/description").asText())
                .contains("type=access", "idempotent");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1logout/post/responses/204");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1auth~1logout/post/responses/401");
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/parameters/0/name").asText())
                .isEqualTo("Authorization");
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/security/0").isObject()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1auth~1logout/post/security/0").size()).isZero();
        assertThat(specification.at("/components/schemas/JwtResponse/properties/accessToken/description").asText())
                .contains("type=access");
        assertThat(specification.at("/components/schemas/JwtResponse/properties/refreshToken/description").asText())
                .contains("cannot authenticate protected endpoints");
    }

    private void assertResponseDocumented(JsonNode specification, String responsePointer) {
        assertThat(specification.at(responsePointer).isMissingNode()).isFalse();
    }
}
