package uk.gegc.quizmaker.features.billing.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Billing configuration preflight")
class BillingConfigurationPreflightTest {

    @Test
    @DisplayName("uses the canonical default when the deployment override is absent")
    void preflight_withoutRatioOverride_usesCanonicalDefault() {
        preflightContext(null).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(BillingProperties.class).getTokenToLlmRatio()).isEqualTo(1000L);
            assertThat(context.getBean(BillingProperties.class).getGeneration().getTariffVersion())
                    .isEqualTo("v1-per-valid-question");
            assertThat(context.getBean(BillingProperties.class).getGeneration().getTokensPerValidQuestion())
                    .isEqualTo(1L);
            BillingConfigurationPreflight.verifyConfiguredRatio(context.getEnvironment());
        });
    }

    @Test
    @DisplayName("accepts a positive per-valid-question tariff rate")
    void preflight_withValidGenerationTariffRate_acceptsTypedConfiguration() {
        preflightContext(null)
                .withPropertyValues(
                        "billing.generation.tariff-version=v1-per-valid-question",
                        "billing.generation.tokens-per-valid-question=3"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBean(BillingProperties.class).getGeneration().getTokensPerValidQuestion())
                            .isEqualTo(3L);
                });
    }

    @Test
    @DisplayName("accepts a valid positive integer ratio")
    void preflight_withValidRatio_acceptsTypedConfiguration() {
        preflightContext("2000").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(BillingProperties.class).getTokenToLlmRatio()).isEqualTo(2000L);
            BillingConfigurationPreflight.verifyConfiguredRatio(context.getEnvironment());
        });
    }

    @ParameterizedTest(name = "rejects invalid ratio [{0}]")
    @ValueSource(strings = {"0", "-1", "1.0", "9223372036854775808", " "})
    @DisplayName("rejects zero, negative, decimal, overflow, and whitespace ratios")
    void preflight_withInvalidRatio_rejectsConfiguration(String ratio) {
        Throwable failure = runPreflightAndCaptureFailure(ratio);

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining("billing.token-to-llm-ratio");
    }

    private Throwable runPreflightAndCaptureFailure(String ratio) {
        AtomicReference<Throwable> failure = new AtomicReference<>();

        preflightContext(ratio).run(context -> {
            if (context.getStartupFailure() != null) {
                failure.set(context.getStartupFailure());
                return;
            }

            try {
                BillingConfigurationPreflight.verifyConfiguredRatio(context.getEnvironment());
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        });

        return failure.get();
    }

    private ApplicationContextRunner preflightContext(String ratio) {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("config-preflight"))
                .withUserConfiguration(BillingConfigurationPreflight.PreflightConfiguration.class);

        return ratio == null
                ? contextRunner
                : contextRunner.withPropertyValues("billing.token-to-llm-ratio=" + ratio);
    }
}
