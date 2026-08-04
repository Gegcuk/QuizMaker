package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("QuizGenerationJob Tariff Snapshot Tests")
class QuizGenerationJobTariffSnapshotTest {

    @Test
    @DisplayName("Legacy job without a tariff snapshot remains on the legacy settlement path")
    void hasGenerationTariffSnapshot_legacyJobWithoutSnapshot_returnsFalse() {
        QuizGenerationJob job = new QuizGenerationJob();

        assertThat(job.hasGenerationTariffSnapshot()).isFalse();
    }

    @Test
    @DisplayName("Capturing a tariff snapshot records every immutable customer-pricing input")
    void captureGenerationTariff_validInputs_recordsCompleteSnapshot() {
        QuizGenerationJob job = new QuizGenerationJob();
        BigDecimal rate = new BigDecimal("0.3500");

        job.captureGenerationTariff(
                "v1-content-length-per-question-type",
                3L,
                rate,
                4_000L,
                2
        );

        assertThat(job.hasGenerationTariffSnapshot()).isTrue();
        assertThat(job.getBillingTariffVersion()).isEqualTo("v1-content-length-per-question-type");
        assertThat(job.getBillingBaseTokens()).isEqualTo(3L);
        assertThat(job.getBillingTokensPerThousandCharacters()).isEqualByComparingTo(rate);
        assertThat(job.getBillingQuotedContentCharacters()).isEqualTo(4_000L);
        assertThat(job.getBillingQuotedQuestionTypeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Capturing a tariff snapshot rejects values that cannot be settled safely")
    void captureGenerationTariff_invalidInputs_rejectsInvalidSnapshots() {
        QuizGenerationJob job = new QuizGenerationJob();

        assertThatIllegalArgumentException().isThrownBy(() -> job.captureGenerationTariff(
                " ", 3L, new BigDecimal("0.35"), 4_000L, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> job.captureGenerationTariff(
                "v1", -1L, new BigDecimal("0.35"), 4_000L, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> job.captureGenerationTariff(
                "v1", 3L, BigDecimal.ZERO, 4_000L, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> job.captureGenerationTariff(
                "v1", 3L, new BigDecimal("0.35"), -1L, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> job.captureGenerationTariff(
                "v1", 3L, new BigDecimal("0.35"), 4_000L, -1));
    }

    @Test
    @DisplayName("Provider LLM usage accumulates separately from customer tariff fields")
    void addProviderLlmTokens_accumulatesOperationalUsageWithoutChangingTariffSnapshot() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.captureGenerationTariff(
                "v1-content-length-per-question-type",
                3L,
                new BigDecimal("0.35"),
                4_000L,
                2
        );

        job.addProviderLlmTokens(120L);
        job.addProviderLlmTokens(80L);

        assertThat(job.getProviderLlmTokens()).isEqualTo(200L);
        assertThat(job.getBillingTariffVersion()).isEqualTo("v1-content-length-per-question-type");
        assertThat(job.getBillingBaseTokens()).isEqualTo(3L);
        assertThat(job.getBillingTokensPerThousandCharacters()).isEqualByComparingTo("0.35");
        assertThat(job.getBillingQuotedContentCharacters()).isEqualTo(4_000L);
        assertThat(job.getBillingQuotedQuestionTypeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Provider LLM usage rejects negative telemetry")
    void addProviderLlmTokens_negativeUsage_rejected() {
        QuizGenerationJob job = new QuizGenerationJob();

        assertThatIllegalArgumentException().isThrownBy(() -> job.addProviderLlmTokens(-1L));
    }
}
