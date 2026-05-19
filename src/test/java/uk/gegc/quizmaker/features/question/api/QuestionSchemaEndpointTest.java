package uk.gegc.quizmaker.features.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionSchemaService;
import uk.gegc.quizmaker.features.question.application.QuestionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuestionSchemaEndpointTest {

    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

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
    void getFillGapSchema_includesOptionsExampleButKeepsOptionsOptionalInPublicSchema() throws Exception {
        String response = mockMvc.perform(get("/api/v1/questions/schemas/FILL_GAP"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
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
    }
}
