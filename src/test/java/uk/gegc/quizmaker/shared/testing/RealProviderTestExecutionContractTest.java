package uk.gegc.quizmaker.shared.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Real provider test execution contract")
class RealProviderTestExecutionContractTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("liveProviderTestClasses")
    @DisplayName("Known live-provider suites use the guarded provider annotation")
    void liveProviderSuites_requireTheGuardedProviderAnnotation(String className) throws ClassNotFoundException {
        Class<?> testClass = Class.forName(className);

        assertThat(testClass.getDeclaredAnnotation(RealProviderTest.class))
                .as("%s must use @RealProviderTest", className)
                .isNotNull();
    }

    @Test
    @DisplayName("Provider annotation requires serial and provider lanes plus explicit opt-in")
    void realProviderAnnotation_definesTagsAndManualOptIn() {
        Set<String> tags = AnnotationSupport
                .findRepeatableAnnotations(RealProviderTest.class, Tag.class)
                .stream()
                .map(Tag::value)
                .collect(java.util.stream.Collectors.toSet());
        EnabledIfSystemProperty optIn = RealProviderTest.class.getAnnotation(EnabledIfSystemProperty.class);

        assertThat(tags).containsExactlyInAnyOrder("db-serial", "real-provider");
        assertThat(optIn).isNotNull();
        assertThat(optIn.named()).isEqualTo("quizmaker.tests.live-provider");
        assertThat(optIn.matches()).isEqualTo("true");
    }

    private static Stream<String> liveProviderTestClasses() {
        return Stream.of(
                "uk.gegc.quizmaker.features.ai.application.impl.RealAiQuizGenerationIntegrationTest",
                "uk.gegc.quizmaker.features.billing.integration.ProductionReadinessValidationTest",
                "uk.gegc.quizmaker.features.billing.integration.RealStripeApiIntegrationTest",
                "uk.gegc.quizmaker.features.billing.integration.RealStripeCliE2ETest",
                "uk.gegc.quizmaker.features.billing.integration.StripeCliE2ETest",
                "uk.gegc.quizmaker.integration.RealAiStripeEndToEndIntegrationTest",
                "uk.gegc.quizmaker.integration.StripeIntegrationTest",
                "uk.gegc.quizmaker.shared.email.RealAwsSesE2ETest"
        );
    }
}
