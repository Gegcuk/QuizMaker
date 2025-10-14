package uk.gegc.quizmaker.features.quiz.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;
import uk.gegc.quizmaker.features.quiz.application.export.impl.JsonExportRenderer;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExportRendererTest {

    private ObjectMapper objectMapper;
    private JsonExportRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        renderer = new JsonExportRenderer(objectMapper);
    }

    @Test
    void renders_quizzes_array_and_respects_filename_prefix() throws Exception {
        QuizExportDto quiz = new QuizExportDto(
                UUID.randomUUID(),
                "Title",
                "Desc",
                Visibility.PUBLIC,
                Difficulty.EASY,
                10,
                List.of("java"),
                "General",
                UUID.randomUUID(),
                List.of(new QuestionExportDto(UUID.randomUUID(), uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE, Difficulty.EASY, "Q?", objectMapper.createObjectNode().put("answer", true), null, null, null)),
                Instant.now(),
                Instant.now()
        );

        ExportPayload payload = new ExportPayload(List.of(quiz), uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions.defaults(), "quizzes_me_202401011200");
        ExportFile file = renderer.render(payload);

        assertThat(file.filename()).isEqualTo("quizzes_me_202401011200.json");
        assertThat(file.contentType()).isEqualTo("application/json");

        try (InputStream is = file.contentSupplier().get()) {
            String json = new String(is.readAllBytes());
            assertThat(json.trim()).startsWith("[");
            assertThat(json).contains("\"Title\"");
            assertThat(json).doesNotContain("printOptions");
        }
    }
}

