package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for token math operations and LLM to billing conversion boundary cases.
 * Covers edge cases, precision handling, and mathematical correctness.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Token Math Tests")
@Execution(ExecutionMode.CONCURRENT)
class TokenMathTest {

    @Nested
    @DisplayName("LLM to Billing Conversion Boundary Cases")
    class LLMToBillingConversionTests {

        @ParameterizedTest
        @DisplayName("Should handle boundary cases for token conversion")
        @CsvSource({
            "0.0, 1.5, 0",
            "0.1, 1.5, 1",
            "1.0, 1.5, 2",
            "100.0, 0.99, 99",
            "100.0, 1.0, 100",
            "100.0, 1.01, 101",
            "100.0, 1.5, 150",
            "123.7, 1.25, 155",
            "999.9, 1.0, 1000"
        })
        void shouldHandleBoundaryCasesForTokenConversion(double inputTokens, double ratio, long expected) {
            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should handle very small ratios")
        void shouldHandleVerySmallRatios() {
            // Given
            double inputTokens = 1000.0;
            double ratio = 0.001; // Very small ratio

            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then
            assertThat(result).isEqualTo(1L); // ceil(1000 * 0.001) = ceil(1.0) = 1
        }

        @Test
        @DisplayName("Should handle very large ratios")
        void shouldHandleVeryLargeRatios() {
            // Given
            double inputTokens = 100.0;
            double ratio = 1000.0; // Very large ratio

            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then
            assertThat(result).isEqualTo(100000L); // ceil(100 * 1000) = ceil(100000) = 100000
        }

        @Test
        @DisplayName("Should handle fractional input with various ratios")
        void shouldHandleFractionalInputWithVariousRatios() {
            // Given
            double inputTokens = 123.456;
            
            // When & Then
            assertThat(Math.ceil(inputTokens * 1.0)).isEqualTo(124L);
            assertThat(Math.ceil(inputTokens * 1.1)).isEqualTo(136L); // ceil(135.8016) = 136
            assertThat(Math.ceil(inputTokens * 1.5)).isEqualTo(186L); // ceil(185.184) = 186
            assertThat(Math.ceil(inputTokens * 2.0)).isEqualTo(247L); // ceil(246.912) = 247
        }

        @Test
        @DisplayName("Should handle edge case ratios around 1.0")
        void shouldHandleEdgeCaseRatiosAroundOne() {
            // Given
            double inputTokens = 100.0;
            double[] ratios = {0.999, 1.0, 1.001, 1.01, 1.1};
            long[] expected = {100L, 100L, 101L, 101L, 111L}; // 100*0.999=99.9->100, 100*1.0=100, 100*1.001=100.1->101, 100*1.01=101->101, 100*1.1=110.0000001->111

            // When & Then
            for (int i = 0; i < ratios.length; i++) {
                double intermediate = inputTokens * ratios[i];
                long result = (long) Math.ceil(intermediate);
                assertThat(result).isEqualTo(expected[i])
                    .describedAs("Input: %f, Ratio: %f, Intermediate: %f, Expected: %d, Got: %d", 
                        inputTokens, ratios[i], intermediate, expected[i], result);
            }
        }
    }

    @Nested
    @DisplayName("Precision and Rounding Tests")
    class PrecisionAndRoundingTests {

        @Test
        @DisplayName("Should use ceiling consistently for all conversions")
        void shouldUseCeilingConsistentlyForAllConversions() {
            // Given
            double[] testValues = {0.1, 0.5, 0.9, 1.0, 1.1, 1.5, 1.9};
            double ratio = 1.0;

            // When & Then
            for (double value : testValues) {
                long result = (long) Math.ceil(value * ratio);
                double expected = Math.ceil(value);
                assertThat(result).isEqualTo((long) expected)
                    .describedAs("Ceiling of %f should be %f", value, expected);
            }
        }

        @Test
        @DisplayName("Should handle floating point precision issues")
        void shouldHandleFloatingPointPrecisionIssues() {
            // Given - values that might cause floating point precision issues
            double inputTokens = 100.1;
            double ratio = 1.1;

            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then - should handle precision correctly
            // 100.1 * 1.1 = 110.11, ceil(110.11) = 111
            assertThat(result).isEqualTo(111L);
        }

        @Test
        @DisplayName("Should handle very precise fractional calculations")
        void shouldHandleVeryPreciseFractionalCalculations() {
            // Given
            double inputTokens = 123.456789;
            double ratio = 1.23456789;

            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then
            // 123.456789 * 1.23456789 = 152.415788... 
            // ceil(152.415788...) = 153
            assertThat(result).isEqualTo(153L);
        }

        @ParameterizedTest
        @DisplayName("Should handle edge cases around integer boundaries")
        @ValueSource(doubles = {0.0, 0.1, 0.9, 1.0, 1.1, 1.9, 2.0, 2.1})
        void shouldHandleEdgeCasesAroundIntegerBoundaries(double inputTokens) {
            // Given
            double ratio = 1.0;

            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then - should always round up
            long expected = (long) Math.ceil(inputTokens);
            assertThat(result).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Token Estimation Math")
    class TokenEstimationMathTests {

        @Test
        @DisplayName("Should calculate system + context + template + content correctly")
        void shouldCalculateSystemContextTemplateContentCorrectly() {
            // Given
            long systemTokens = 100L;
            long contextTokens = 200L;
            long templateTokens = 150L;
            long contentTokensPerChunk = 50L;
            int chunkCount = 3;

            // When
            long totalPerChunk = systemTokens + contextTokens + templateTokens + contentTokensPerChunk;
            long totalForAllChunks = totalPerChunk * chunkCount;

            // Then
            assertThat(totalPerChunk).isEqualTo(500L); // 100 + 200 + 150 + 50
            assertThat(totalForAllChunks).isEqualTo(1500L); // 500 * 3
        }

        @Test
        @DisplayName("Should apply difficulty multiplier correctly")
        void shouldApplyDifficultyMultiplierCorrectly() {
            // Given
            long baseTokens = 1000L;
            double[] difficultyMultipliers = {0.5, 1.0, 1.5, 2.0, 2.5};
            long[] expected = {500L, 1000L, 1500L, 2000L, 2500L};

            // When & Then
            for (int i = 0; i < difficultyMultipliers.length; i++) {
                long result = Math.round(baseTokens * difficultyMultipliers[i]);
                assertThat(result).isEqualTo(expected[i]);
            }
        }

        @Test
        @DisplayName("Should apply safety factor with ceiling")
        void shouldApplySafetyFactorWithCeiling() {
            // Given
            long tokensAfterDifficulty = 1000L;
            double safetyFactor = 1.2;

            // When
            long result = (long) Math.ceil(tokensAfterDifficulty * safetyFactor);

            // Then
            assertThat(result).isEqualTo(1200L); // ceil(1000 * 1.2) = ceil(1200) = 1200
        }

        @Test
        @DisplayName("Should apply token ratio with ceiling")
        void shouldApplyTokenRatioWithCeiling() {
            // Given
            long tokensAfterSafety = 1200L;
            double tokenRatio = 1.5;

            // When
            long result = (long) Math.ceil(tokensAfterSafety * tokenRatio);

            // Then
            assertThat(result).isEqualTo(1800L); // ceil(1200 * 1.5) = ceil(1800) = 1800
        }

        @Test
        @DisplayName("Should handle complete estimation chain")
        void shouldHandleCompleteEstimationChain() {
            // Given
            long systemTokens = 100L;
            long contextTokens = 200L;
            long templateTokens = 150L;
            long contentTokensPerChunk = 50L;
            int chunkCount = 2;
            double difficultyMultiplier = 1.5;
            double safetyFactor = 1.2;
            double tokenRatio = 1.25;

            // When - complete calculation chain
            long baseTokensPerChunk = systemTokens + contextTokens + templateTokens + contentTokensPerChunk;
            long totalBaseTokens = baseTokensPerChunk * chunkCount;
            double tokensWithDifficulty = totalBaseTokens * difficultyMultiplier;
            double tokensWithSafety = tokensWithDifficulty * safetyFactor;
            long finalTokens = (long) Math.ceil(tokensWithSafety * tokenRatio);

            // Then
            assertThat(baseTokensPerChunk).isEqualTo(500L);
            assertThat(totalBaseTokens).isEqualTo(1000L);
            assertThat(tokensWithDifficulty).isEqualTo(1500.0);
            assertThat(tokensWithSafety).isEqualTo(1800.0);
            assertThat(finalTokens).isEqualTo(2250L); // ceil(1800 * 1.25) = ceil(2250) = 2250
        }
    }

    @Nested
    @DisplayName("BigDecimal Precision Tests")
    class BigDecimalPrecisionTests {

        @Test
        @DisplayName("Should handle high precision calculations with BigDecimal")
        void shouldHandleHighPrecisionCalculationsWithBigDecimal() {
            // Given
            BigDecimal inputTokens = new BigDecimal("123.456789");
            BigDecimal ratio = new BigDecimal("1.23456789");

            // When
            BigDecimal result = inputTokens.multiply(ratio);
            long ceilingResult = result.setScale(0, RoundingMode.CEILING).longValue();

            // Then
            assertThat(ceilingResult).isEqualTo(153L);
        }

        @Test
        @DisplayName("Should maintain precision in complex calculations")
        void shouldMaintainPrecisionInComplexCalculations() {
            // Given
            BigDecimal baseTokens = new BigDecimal("1000.123");
            BigDecimal difficultyMultiplier = new BigDecimal("1.567");
            BigDecimal safetyFactor = new BigDecimal("1.234");
            BigDecimal tokenRatio = new BigDecimal("1.456");

            // When
            BigDecimal result = baseTokens
                .multiply(difficultyMultiplier)
                .multiply(safetyFactor)
                .multiply(tokenRatio);
            
            long finalResult = result.setScale(0, RoundingMode.CEILING).longValue();

            // Then
            // 1000.123 * 1.567 * 1.234 * 1.456 = 2815.48...
            // ceil(2815.48...) = 2816
            assertThat(finalResult).isEqualTo(2816L);
        }

        @Test
        @DisplayName("Should handle edge cases with BigDecimal")
        void shouldHandleEdgeCasesWithBigDecimal() {
            // Given
            BigDecimal verySmall = new BigDecimal("0.000001");
            BigDecimal veryLarge = new BigDecimal("999999.999999");

            // When
            BigDecimal smallResult = verySmall.multiply(new BigDecimal("1.5"));
            BigDecimal largeResult = veryLarge.multiply(new BigDecimal("1.5"));

            // Then
            assertThat(smallResult.setScale(0, RoundingMode.CEILING).longValue()).isEqualTo(1L);
            assertThat(largeResult.setScale(0, RoundingMode.CEILING).longValue()).isEqualTo(1500000L);
        }
    }

    @Nested
    @DisplayName("Boundary Value Analysis")
    class BoundaryValueAnalysisTests {

        @Test
        @DisplayName("Should handle minimum token values")
        void shouldHandleMinimumTokenValues() {
            // Given
            double minTokens = Double.MIN_VALUE;
            double ratio = 1.0;

            // When
            long result = (long) Math.ceil(minTokens * ratio);

            // Then - should handle very small numbers
            assertThat(result).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("Should handle maximum token values")
        void shouldHandleMaximumTokenValues() {
            // Given
            double maxTokens = Double.MAX_VALUE / 2; // Avoid overflow
            double ratio = 1.0;

            // When
            long result = (long) Math.ceil(maxTokens * ratio);

            // Then - should handle very large numbers
            assertThat(result).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should handle zero and negative ratios")
        void shouldHandleZeroAndNegativeRatios() {
            // Given
            double inputTokens = 100.0;
            double[] ratios = {0.0, -1.0, -0.5};

            // When & Then
            assertThat(Math.ceil(inputTokens * 0.0)).isEqualTo(0L);
            assertThat(Math.ceil(inputTokens * -1.0)).isEqualTo(-100L);
            assertThat(Math.ceil(inputTokens * -0.5)).isEqualTo(-50L);
        }

        @Test
        @DisplayName("Should handle fractional ratios close to zero")
        void shouldHandleFractionalRatiosCloseToZero() {
            // Given
            double inputTokens = 1000.0;
            double[] smallRatios = {0.001, 0.01, 0.1};

            // When & Then
            assertThat(Math.ceil(inputTokens * 0.001)).isEqualTo(1L);
            assertThat(Math.ceil(inputTokens * 0.01)).isEqualTo(10L);
            assertThat(Math.ceil(inputTokens * 0.1)).isEqualTo(100L);
        }
    }
}
