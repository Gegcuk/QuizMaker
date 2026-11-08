package uk.gegc.quizmaker.features.attempt.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import uk.gegc.quizmaker.features.attempt.application.NavigationService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NavigationActivationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(NavigationService.class, () -> mock(NavigationService.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withUserConfiguration(NavigationController.class);

    @Test void absentOrFalseGateDoesNotRegisterTheEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(NavigationController.class));
        runner.withPropertyValues("quizmaker.attempts.navigation-commands-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NavigationController.class));
    }
    @Test void explicitTestActivationRegistersTheEndpoint() {
        runner.withPropertyValues("quizmaker.attempts.navigation-commands-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(NavigationController.class));
    }
}
