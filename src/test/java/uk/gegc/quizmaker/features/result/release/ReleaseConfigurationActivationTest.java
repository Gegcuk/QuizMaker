package uk.gegc.quizmaker.features.result.release;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import uk.gegc.quizmaker.features.result.api.ReleaseConfigurationController;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseConfigurationActivationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(ReleaseConfigurationService.class, () -> mock(ReleaseConfigurationService.class))
            .withUserConfiguration(ReleaseConfigurationController.class);
    @Test void authoringIsAbsentUntilExplicitlyEnabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ReleaseConfigurationController.class));
        runner.withPropertyValues("quizmaker.results.release-configuration-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReleaseConfigurationController.class));
        runner.withPropertyValues("quizmaker.results.release-configuration-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ReleaseConfigurationController.class));
    }
}
