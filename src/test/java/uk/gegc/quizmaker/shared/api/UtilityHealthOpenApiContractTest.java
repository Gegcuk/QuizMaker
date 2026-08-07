package uk.gegc.quizmaker.shared.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UtilityController.class)
@Import({
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        UtilityHealthOpenApiContractTest.SpringDocTestConfig.class
})
@DisplayName("Utility health OpenAPI contract")
class UtilityHealthOpenApiContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApplicationAvailability applicationAvailability;

    @Test
    @WithMockUser
    @DisplayName("Admin API group documents the public status-only compatibility endpoint")
    void adminSpec_documentsMinimalHealthContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/admin"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        JsonNode operation = specification.at("/paths/~1api~1v1~1health/get");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").isArray()).isTrue();
        assertThat(operation.path("security")).hasSize(1);
        assertThat(operation.path("security").get(0).isObject()).isTrue();
        assertThat(operation.path("security").get(0)).isEmpty();
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/HealthStatusResponse");
        assertThat(operation.at("/responses/503/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/HealthStatusResponse");
        JsonNode properties = specification.at("/components/schemas/HealthStatusResponse/properties");
        assertThat(properties.fieldNames()).toIterable().containsExactly("status");
    }
}
