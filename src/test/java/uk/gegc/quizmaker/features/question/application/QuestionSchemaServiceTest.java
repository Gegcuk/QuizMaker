package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.api.dto.QuestionSchemaResponse;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionSchemaServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestionSchemaService service = new QuestionSchemaService(
            new QuestionSchemaRegistry(objectMapper),
            objectMapper);

    @Test
    void getQuestionSchema_fillGapExampleIncludesOptions() {
        QuestionSchemaResponse response = service.getQuestionSchema(QuestionType.FILL_GAP);

        JsonNode content = response.example().get("content");
        assertThat(content.has("text")).isTrue();
        assertThat(content.has("gaps")).isTrue();
        assertThat(content.has("options")).isTrue();
        assertThat(content.get("options").isArray()).isTrue();
        assertThat(content.get("options").size()).isEqualTo(8);
    }
}
