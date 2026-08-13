package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FillGapPromptResourceTest {

    @Test
    @DisplayName("fill-gap prompt requires answers, preferred distractors, and a ten-option cap")
    void fillGapPromptResource_requiresOptionsPool() throws Exception {
        ClassPathResource resource = new ClassPathResource("prompts/question-types/fill-gap.txt");

        assertThat(resource.exists()).isTrue();

        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(prompt).contains("\"options\" array");
        assertThat(prompt).contains("all correct answers");
        assertThat(prompt).contains("at least 6 plausible distractors");
        assertThat(prompt).contains("Every value from gaps[].answer must appear in options");
        assertThat(prompt).contains("Prefer 6-7 additional plausible but incorrect distractors");
        assertThat(prompt).contains("MUST NOT exceed 10 total items");
        assertThat(prompt).contains("at least number of gaps + 6 and at most 10");
        assertThat(prompt).contains("2 gaps = 8-10");
    }
}
