package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedNormalizedDocumentUtilsTest {

    @Test
    void clamp_withinBounds_returnsSame() {
        assertThat(DocumentUtils.clamp(5, 0, 10)).isEqualTo(5);
        assertThat(DocumentUtils.clamp(0, 0, 10)).isEqualTo(0);
        assertThat(DocumentUtils.clamp(10, 0, 10)).isEqualTo(10);
    }

    @Test
    void clamp_belowMin_returnsMin() {
        assertThat(DocumentUtils.clamp(-5, 0, 10)).isEqualTo(0);
        assertThat(DocumentUtils.clamp(0, 5, 10)).isEqualTo(5);
    }

    @Test
    void clamp_aboveMax_returnsMax() {
        assertThat(DocumentUtils.clamp(15, 0, 10)).isEqualTo(10);
        assertThat(DocumentUtils.clamp(10, 0, 5)).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({
        "5, 0, 10, 5",
        "0, 0, 10, 0", 
        "10, 0, 10, 10",
        "-5, 0, 10, 0",
        "15, 0, 10, 10",
        "100, 50, 200, 100",
        "-100, -50, 50, -50"
    })
    void clamp_variousInputs_returnsExpected(int value, int min, int max, int expected) {
        assertThat(DocumentUtils.clamp(value, min, max)).isEqualTo(expected);
    }

    @Test
    void clamp_edgeCases() {
        // Same min and max
        assertThat(DocumentUtils.clamp(5, 10, 10)).isEqualTo(10);
        
        // Negative ranges
        assertThat(DocumentUtils.clamp(-5, -10, -1)).isEqualTo(-5);
        assertThat(DocumentUtils.clamp(-15, -10, -1)).isEqualTo(-10);
        assertThat(DocumentUtils.clamp(5, -10, -1)).isEqualTo(-1);
    }
}
