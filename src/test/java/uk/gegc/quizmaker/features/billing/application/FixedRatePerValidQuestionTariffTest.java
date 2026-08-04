package uk.gegc.quizmaker.features.billing.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Fixed-rate generation tariff")
class FixedRatePerValidQuestionTariffTest {

    @Test
    @DisplayName("quotes every requested question at the configured fixed rate")
    void quotesEveryRequestedQuestionAtConfiguredFixedRate() {
        GenerationTariff tariff = new FixedRatePerValidQuestionTariff("v1-per-valid-question", 3L);

        assertThat(tariff.quoteForRequestedQuestions(7)).isEqualTo(21L);
        assertThat(tariff.version()).isEqualTo("v1-per-valid-question");
        assertThat(tariff.tokensPerValidQuestion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("settles accepted questions without exceeding the stored maximum quote")
    void settlesAcceptedQuestionsWithoutExceedingStoredMaximumQuote() {
        GenerationTariff tariff = new FixedRatePerValidQuestionTariff("v1-per-valid-question", 3L);

        assertThat(tariff.settlementForAcceptedQuestions(4, 21L)).isEqualTo(12L);
        assertThat(tariff.settlementForAcceptedQuestions(9, 21L)).isEqualTo(21L);
    }

    @Test
    @DisplayName("rejects invalid pricing inputs rather than silently changing customer charges")
    void rejectsInvalidPricingInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixedRatePerValidQuestionTariff("", 1L));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixedRatePerValidQuestionTariff("v1", 0L));

        GenerationTariff tariff = new FixedRatePerValidQuestionTariff("v1", 1L);
        assertThatThrownBy(() -> tariff.quoteForRequestedQuestions(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tariff.settlementForAcceptedQuestions(1, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
