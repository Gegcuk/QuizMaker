package uk.gegc.quizmaker.features.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionSchemaService;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuestionSchemaEndpointTest {

    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @Test
    void hotspotDiscoverySeparatesStrictNewAiOutputFromOptionalManualDescriptions() throws Exception {
        var response = mockMvc.perform(get("/api/v1/questions/schemas/HOTSPOT")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(response);
        var ai = document.at("/schema/properties/questions/items/properties/content");
        assertThat(ai.path("required").toString()).contains("imageDescription");
        assertThat(ai.at("/properties/regions/items/required").toString()).contains("label", "description");
        for (String view : java.util.List.of("author", "safe")) {
            var manual = document.path("contracts").path(view);
            assertThat(manual.path("required").toString()).doesNotContain("imageDescription");
            assertThat(manual.at("/properties/regions/items/required").toString()).doesNotContain("label", "description");
            assertThat(manual.at("/properties/imageDescription/maxLength").asInt()).isEqualTo(2_000);
        }
        assertThat(document.at("/example/content/imageDescription").asText()).isNotBlank();
        assertThat(document.at("/example/content/regions/0/label").asText()).isNotBlank();
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        QuestionSchemaService schemaService = new QuestionSchemaService(
                new QuestionSchemaRegistry(objectMapper),
                objectMapper);
        QuestionController controller = new QuestionController(mock(QuestionService.class), schemaService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Public fill-gap schema documents optional typed mode and bounded drag options")
    void getFillGapSchema_includesOptionsExampleButKeepsOptionsOptionalInPublicSchema() throws Exception {
        String response = mockMvc.perform(get("/api/v1/questions/schemas/FILL_GAP"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("description").asText())
                .contains("optional options array enables drag-and-drop mode")
                .contains("without options, clients should render typed blanks");

        JsonNode content = json.get("example").get("content");
        assertThat(content.has("options")).isTrue();
        assertThat(content.get("options").isArray()).isTrue();
        assertThat(content.get("options").size()).isGreaterThanOrEqualTo(7);

        JsonNode contentSchema = json.get("schema")
                .get("properties")
                .get("questions")
                .get("items")
                .get("properties")
                .get("content");
        assertThat(contentSchema.get("properties").has("options")).isTrue();
        assertThat(contentSchema.get("required").toString()).doesNotContain("options");
        assertThat(contentSchema.get("description").asText())
                .contains("'options' is optional")
                .contains("If omitted, render blanks for typed answers")
                .contains("If present, render the values as drag-and-drop options");
        JsonNode optionsSchema = contentSchema.get("properties").get("options");
        assertThat(optionsSchema.get("minItems").asInt()).isEqualTo(7);
        assertThat(optionsSchema.get("maxItems").asInt()).isEqualTo(10);
        assertThat(optionsSchema.get("description").asText())
                .contains("include every gaps[].answer value")
                .contains("at least 6 plausible but incorrect distractors")
                .contains("Prefer 6-7 distractors")
                .contains("no more than 10 total options")
                .contains("same domain/category")
                .contains("must not be synonyms or alternate correct answers");
    }

    @Test
    @DisplayName("Public schema index exposes a complete contract for every question type")
    void getAllSchemas_exposesEveryQuestionTypeWithContractFields() throws Exception {
        String response = mockMvc.perform(get("/api/v1/questions/schemas"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode schemas = objectMapper.readTree(response);
        assertThat(schemas).hasSize(QuestionType.values().length);
        for (QuestionType type : QuestionType.values()) {
            JsonNode contract = schemas.path(type.name());
            assertThat(contract.isMissingNode()).as("schema for %s", type).isFalse();
            assertThat(contract.path("schema").isObject()).as("JSON schema for %s", type).isTrue();
            assertThat(contract.path("example").isObject()).as("example for %s", type).isTrue();
            assertThat(contract.path("description").asText()).as("description for %s", type).isNotBlank();
            assertThat(contract.at("/contracts/version").asInt()).isEqualTo(1);
            for (String view : java.util.List.of("author", "safe", "response", "correct")) {
                assertThat(contract.path("contracts").path(view).path("type").asText()).isEqualTo("object");
            }
            if (type == QuestionType.MCQ_SINGLE) {
                assertThat(contract.at("/contracts/author/properties/options/minItems").asInt()).isEqualTo(2);
            }
        }
    }
}
