package uk.gegc.quizmaker.features.attempt.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import uk.gegc.quizmaker.features.attempt.application.DraftCommandService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DraftCommandActivationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(DraftCommandService.class, () -> mock(DraftCommandService.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withUserConfiguration(DraftCommandController.class);

    @Test void absentOrFalseGateDoesNotRegisterTheEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DraftCommandController.class));
        runner.withPropertyValues("quizmaker.attempts.draft-commands-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DraftCommandController.class));
    }
    @Test void explicitTestActivationRegistersTheEndpoint() {
        runner.withPropertyValues("quizmaker.attempts.draft-commands-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DraftCommandController.class));
    }
}
