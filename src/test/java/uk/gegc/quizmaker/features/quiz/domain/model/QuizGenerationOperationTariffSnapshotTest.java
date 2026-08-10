package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("Quiz generation operation tariff snapshot")
class QuizGenerationOperationTariffSnapshotTest {

    @Test
    @DisplayName("Captures a complete tariff snapshot for a claimed command")
    void captureGenerationTariffSnapshotStoresCompleteSnapshot() {
        QuizGenerationOperation operation = new QuizGenerationOperation();

        operation.captureGenerationTariffSnapshot("v1-content-length", 3L, new BigDecimal("0.350000"));

        assertThat(operation.hasGenerationTariffSnapshot()).isTrue();
        assertThat(operation.getBillingTariffVersion()).isEqualTo("v1-content-length");
        assertThat(operation.getBillingBaseTokens()).isEqualTo(3L);
        assertThat(operation.getBillingTokensPerThousandCharacters()).isEqualByComparingTo("0.350000");
        assertThatIllegalStateException()
                .isThrownBy(() -> operation.captureGenerationTariffSnapshot(
                        "v2-content-length", 5L, new BigDecimal("0.50")))
                .withMessage("Generation tariff snapshot is immutable once captured");
    }

    @Test
    @DisplayName("Rejects incomplete or invalid tariff values")
    void captureGenerationTariffSnapshotRejectsInvalidValues() {
        QuizGenerationOperation operation = new QuizGenerationOperation();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> operation.captureGenerationTariffSnapshot(" ", 3L, new BigDecimal("0.35")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> operation.captureGenerationTariffSnapshot("v1", -1L, new BigDecimal("0.35")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> operation.captureGenerationTariffSnapshot("v1", 3L, BigDecimal.ZERO));

        assertThat(operation.hasGenerationTariffSnapshot()).isFalse();
    }
}
