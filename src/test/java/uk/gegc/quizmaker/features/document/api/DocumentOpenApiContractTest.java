package uk.gegc.quizmaker.features.document.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import org.springframework.http.ProblemDetail;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.document.api.dto.DocumentPageResponse;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingConfig;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.application.DocumentValidationService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.documentProcess.api.DocumentProcessController;
import uk.gegc.quizmaker.features.documentProcess.api.dto.DocumentView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {DocumentController.class, DocumentProcessController.class})
@Import({
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        DocumentOpenApiContractTest.SpringDocTestConfig.class
})
class DocumentOpenApiContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentValidationService documentValidationService;

    @MockitoBean
    private DocumentProcessingConfig documentConfig;

    @MockitoBean
    private DocumentIngestionService documentIngestionService;

    @MockitoBean
    private DocumentQueryService documentQueryService;

    @MockitoBean
    private StructureService structureService;

    @MockitoBean
    private DocumentMapper documentMapper;

    @Test
    @WithMockUser
    @DisplayName("GET /v3/api-docs/documents publishes typed list, metadata, and structure contracts")
    void documentsOpenApiContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/documents"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(specification.path("paths").has("/api/documents")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/documentProcess/documents/{id}")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/documentProcess/documents/{id}/structure")).isTrue();

        assertThat(specification.at("/paths/~1api~1documents/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/DocumentPageResponse");
        assertThat(specification.at("/components/schemas/DocumentPageResponse/properties/content/items/$ref").asText())
                .isEqualTo("#/components/schemas/DocumentDto");
        assertThat(specification.at("/components/schemas/DocumentDto/properties/status/example").asText())
                .isEqualTo("PROCESSED");
        assertThat(specification.at("/components/schemas/DocumentDto/properties/status/enum").toString())
                .contains("PROCESSED");

        JsonNode documentPageExample = specification.at("/paths/~1api~1documents/get/responses/200/content/application~1json/examples/Processed documents/value");
        DocumentPageResponse page = strictObjectMapper().treeToValue(documentPageExample, DocumentPageResponse.class);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).getStatus()).isEqualTo(Document.DocumentStatus.PROCESSED);
        assertThat(page.pageable().pageSize()).isEqualTo(10);

        assertThat(specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/DocumentView");
        assertThat(specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1head/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/DocumentView");
        JsonNode metadataExample = specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}/get/responses/200/content/application~1json/examples/Structured text document/value");
        assertThat(specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1head/get/responses/200/content/application~1json/examples/Structured text document/value"))
                .isEqualTo(metadataExample);
        DocumentView metadata = strictObjectMapper().treeToValue(metadataExample, DocumentView.class);
        assertThat(metadata.source().name()).isEqualTo("TEXT");
        assertThat(metadata.status().name()).isEqualTo("STRUCTURED");

        JsonNode structureSchema = specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1structure/get/responses/200/content/application~1json/schema");
        assertThat(structureSchema.path("oneOf").size()).isEqualTo(2);
        assertThat(structureSchema.path("oneOf").toString())
                .contains("#/components/schemas/StructureTreeResponse", "#/components/schemas/StructureFlatResponse");
        assertThat(parameterNamed(specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1structure/get/parameters"), "format")
                .path("schema").path("enum").toString())
                .contains("tree", "flat");

        JsonNode invalidFormatResponse = specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1structure/get/responses/400");
        assertThat(invalidFormatResponse.at("/content/application~1problem+json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ProblemDetail");
        JsonNode invalidFormatExample = invalidFormatResponse
                .at("/content/application~1problem+json/examples/Invalid structure format/value");
        ProblemDetail invalidFormatProblem = strictObjectMapper().treeToValue(invalidFormatExample, ProblemDetail.class);
        assertThat(invalidFormatProblem.getStatus()).isEqualTo(400);
        assertThat(invalidFormatProblem.getType().toString())
                .isEqualTo("https://quizzence.com/docs/errors/invalid-argument");
        assertThat(invalidFormatProblem.getTitle()).isEqualTo("Invalid Argument");
        assertThat(invalidFormatProblem.getDetail()).isEqualTo("Invalid format. Use 'tree' or 'flat'");

        JsonNode treeExample = specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1structure/get/responses/200/content/application~1json/examples/Tree structure (format=tree)/value");
        StructureTreeResponse tree = strictObjectMapper().treeToValue(treeExample, StructureTreeResponse.class);
        assertThat(tree.rootNodes()).hasSize(1);
        assertThat(tree.rootNodes().get(0).children()).hasSize(1);

        JsonNode flatExample = specification.at("/paths/~1api~1v1~1documentProcess~1documents~1{id}~1structure/get/responses/200/content/application~1json/examples/Flat structure (format=flat)/value");
        StructureFlatResponse flat = strictObjectMapper().treeToValue(flatExample, StructureFlatResponse.class);
        assertThat(flat.nodes()).hasSize(2);
        assertThat(flat.nodes().get(0).parentId()).isNull();
    }

    private ObjectMapper strictObjectMapper() {
        return objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private JsonNode parameterNamed(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }

        throw new AssertionError("OpenAPI parameter not found: " + name);
    }
}
