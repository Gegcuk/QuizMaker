package uk.gegc.quizmaker.features.billing.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Content-length per-question-type generation tariff")
class ContentLengthPerQuestionTypeTariffTest {

    private final GenerationTariff tariff = new ContentLengthPerQuestionTypeTariff(
            "v1-content-length-per-question-type",
            3L,
            new BigDecimal("0.35")
    );

    @Test
    @DisplayName("matches the frontend quote formula for source length and active question types")
    void quote_matchesFrontendFormula() {
        assertThat(tariff.quoteForContent(4_000L, 2)).isEqualTo(7L);
        assertThat(tariff.quoteForContent(1L, 1)).isEqualTo(4L);
        assertThat(tariff.quoteForContent(0L, 0)).isEqualTo(3L);
    }

    @Test
    @DisplayName("adds source-content work for each active question type")
    void quote_addsSourceContentWorkForEachActiveQuestionType() {
        assertThat(tariff.quoteForContent(10_000L, 1)).isEqualTo(7L);
        assertThat(tariff.quoteForContent(10_000L, 2)).isEqualTo(11L);
    }

    @Test
    @DisplayName("settles only accepted question types and never exceeds the stored maximum quote")
    void settlement_usesAcceptedQuestionTypesAndCapsAtQuote() {
        assertThat(tariff.settlementForAcceptedQuestionTypes(4_000L, 1, 7L)).isEqualTo(5L);
        assertThat(tariff.settlementForAcceptedQuestionTypes(4_000L, 2, 6L)).isEqualTo(6L);
        assertThat(tariff.settlementForAcceptedQuestionTypes(4_000L, 0, 7L)).isZero();
    }

    @Test
    @DisplayName("rejects invalid pricing inputs rather than silently changing customer charges")
    void rejectsInvalidPricingInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentLengthPerQuestionTypeTariff("", 3L, new BigDecimal("0.35")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentLengthPerQuestionTypeTariff("v1", -1L, new BigDecimal("0.35")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentLengthPerQuestionTypeTariff("v1", 3L, BigDecimal.ZERO));

        assertThatThrownBy(() -> tariff.quoteForContent(-1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tariff.settlementForAcceptedQuestionTypes(1L, 1, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
