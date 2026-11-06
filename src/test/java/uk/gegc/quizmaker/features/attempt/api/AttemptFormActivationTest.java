package uk.gegc.quizmaker.features.attempt.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import uk.gegc.quizmaker.features.attempt.application.AttemptDeliveryService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AttemptFormActivationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(AttemptDeliveryService.class, () -> mock(AttemptDeliveryService.class))
            .withUserConfiguration(AttemptFormController.class);

    @Test void absentOrFalseGateDoesNotRegisterTheEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AttemptFormController.class));
        runner.withPropertyValues("quizmaker.attempts.flat-delivery-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AttemptFormController.class));
    }
    @Test void explicitTestActivationRegistersTheEndpoint() {
        runner.withPropertyValues("quizmaker.attempts.flat-delivery-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AttemptFormController.class));
    }
}
