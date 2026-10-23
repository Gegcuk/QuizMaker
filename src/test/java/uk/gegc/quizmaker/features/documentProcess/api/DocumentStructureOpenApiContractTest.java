package uk.gegc.quizmaker.features.documentProcess.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessService;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;
import uk.gegc.quizmaker.shared.api.docs.ApiDiscoveryController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocumentationService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {DocumentProcessController.class, ApiDiscoveryController.class})
@Import({OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        DocumentStructureOpenApiContractTest.DocConfig.class})
class DocumentStructureOpenApiContractTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired java.util.List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean NormalizedDocumentAccessService access;
    @MockitoBean DocumentMapper mapper;

    @Test
    @WithMockUser
    void groupAndFullSpecDescribeCompatibleBuildAndFailureSchemas() throws Exception {
        String path = "/api/v1/documentProcess/documents/{id}/structure";
        for (String url : new String[]{"/v3/api-docs/documents", "/v3/api-docs"}) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var operation = spec.path("paths").path(path).path("post");
            assertThat(operation.path("security").isEmpty()).isFalse();
            for (String status : new String[]{"200", "400", "500"}) {
                assertThat(operation.path("responses").path(status).path("content").path("application/json")
                        .path("schema").path("$ref").asText()).endsWith("/StructureBuildResponse");
            }
            for (String status : new String[]{"401", "404"}) {
                assertThat(operation.path("responses").path(status).toString()).contains("ProblemDetail");
            }
            var schema = spec.path("components").path("schemas").path("StructureBuildResponse");
            assertThat(schema.path("properties").has("status")).isTrue();
            assertThat(schema.path("properties").has("message")).isTrue();
            for (String status : new String[]{"200", "400"}) {
                var value = operation.path("responses").path(status).path("content").path("application/json").path("example");
                assertThat(value.isObject()).isTrue();
                assertThat(value.path("status").asText()).isEqualTo(status.equals("200") ? "STRUCTURED" : "FAILED");
                assertThat(value.path("message").isTextual()).isTrue();
            }
        }
        var summary = json.readTree(mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(summary.path("groups").findValuesAsText("specUrl")).contains("/v3/api-docs/documents");
        long matches = groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p ->
                        new org.springframework.util.AntPathMatcher().match(p, path))).count();
        assertThat(matches).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void staleBuildRetainsExistingSafe400Envelope() throws Exception {
        doThrow(new IllegalStateException("CANARY_PRIVATE_SOURCE")).when(access).buildStructure(anyString(), any());
        mvc.perform(post("/api/v1/documentProcess/documents/{id}/structure", java.util.UUID.randomUUID()).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Structure could not be built"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("CANARY"))));
    }
}
