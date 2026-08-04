package uk.gegc.quizmaker.features.billing.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

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

    @ParameterizedTest(name = "{0} characters and {1} active types quotes {2} billing tokens")
    @MethodSource("tariffFormulaVectors")
    @DisplayName("quotes the tariff contract across minimum and rounding boundaries")
    void quote_matchesTariffContractAcrossMinimumAndRoundingBoundaries(
            long sourceCharacters,
            int activeQuestionTypes,
            long expectedBillingTokens
    ) {
        assertThat(tariff.quoteForContent(sourceCharacters, activeQuestionTypes))
                .isEqualTo(expectedBillingTokens);
    }

    private static Stream<Arguments> tariffFormulaVectors() {
        return Stream.of(
                Arguments.of(0L, 0, 3L),
                Arguments.of(0L, 1, 3L),
                Arguments.of(1L, 1, 4L),
                Arguments.of(2_857L, 1, 4L),
                Arguments.of(2_858L, 1, 5L),
                Arguments.of(2_857L, 2, 5L),
                Arguments.of(2_858L, 2, 7L),
                Arguments.of(4_000L, 2, 7L),
                Arguments.of(10_000L, 1, 7L),
                Arguments.of(10_000L, 2, 11L)
        );
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
