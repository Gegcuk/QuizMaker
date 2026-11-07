package uk.gegc.quizmaker.features.attempt.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResponseAcceptanceActivationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(ResponseAcceptanceService.class, () -> mock(ResponseAcceptanceService.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withUserConfiguration(ResponseAcceptanceController.class);

    @Test void absentOrFalseGateDoesNotRegisterTheEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ResponseAcceptanceController.class));
        runner.withPropertyValues("quizmaker.attempts.response-acceptance-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ResponseAcceptanceController.class));
    }
    @Test void explicitTestActivationRegistersTheEndpoint() {
        runner.withPropertyValues("quizmaker.attempts.response-acceptance-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ResponseAcceptanceController.class));
    }
}
