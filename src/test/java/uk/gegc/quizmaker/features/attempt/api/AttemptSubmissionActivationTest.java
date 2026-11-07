package uk.gegc.quizmaker.features.attempt.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AttemptSubmissionActivationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(AttemptSubmissionService.class, () -> mock(AttemptSubmissionService.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withUserConfiguration(AttemptSubmissionController.class);

    @Test void absentOrFalseGateDoesNotRegisterTheEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AttemptSubmissionController.class));
        runner.withPropertyValues("quizmaker.attempts.submission-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AttemptSubmissionController.class));
    }
    @Test void explicitTestActivationRegistersTheEndpoint() {
        runner.withPropertyValues("quizmaker.attempts.submission-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AttemptSubmissionController.class));
    }
}
