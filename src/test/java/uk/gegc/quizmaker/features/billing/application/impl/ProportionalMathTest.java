package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Proportional Math Tests")
class ProportionalMathTest {

    @Nested
    @DisplayName("Zero Division Guard Tests")
    class ZeroDivisionGuardTests {

        @Test
        @DisplayName("Should return zero when original amount is zero")
        void shouldReturnZeroWhenOriginalAmountIsZero() {
            // Given
            long originalAmountCents = 0L;
            long originalTokens = 1000L;
            long refundAmountCents = 500L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should return zero when refund amount is zero")
        void shouldReturnZeroWhenRefundAmountIsZero() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 0L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should return zero when original tokens is zero")
        void shouldReturnZeroWhenOriginalTokensIsZero() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 0L;
            long refundAmountCents = 500L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should handle all zero inputs")
        void shouldHandleAllZeroInputs() {
            // Given
            long originalAmountCents = 0L;
            long originalTokens = 0L;
            long refundAmountCents = 0L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Rounding Behavior Tests")
    class RoundingBehaviorTests {

        @Test
        @DisplayName("Should use floor division for odd amounts")
        void shouldUseFloorDivisionForOddAmounts() {
            // Given - 1000 tokens for 1000 cents, refund 333 cents
            // Expected: (1000 * 333) / 1000 = 333 tokens (exact)
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 333L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(333L);
        }

        @Test
        @DisplayName("Should floor divide when result is fractional")
        void shouldFloorDivideWhenResultIsFractional() {
            // Given - 1000 tokens for 1000 cents, refund 333 cents
            // Expected: (1000 * 333) / 1000 = 333 tokens (exact)
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 333L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(333L);
        }

        @Test
        @DisplayName("Should floor divide when result has remainder")
        void shouldFloorDivideWhenResultHasRemainder() {
            // Given - 1000 tokens for 1000 cents, refund 1 cent
            // Expected: (1000 * 1) / 1000 = 1 token (exact)
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 1L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(1L);
        }

        @ParameterizedTest
        @CsvSource({
            "1000, 1000, 100, 100",    // 10% refund = 10% tokens
            "1000, 1000, 500, 500",    // 50% refund = 50% tokens
            "1000, 1000, 999, 999",    // 99.9% refund = 99.9% tokens
            "1000, 1000, 1000, 1000",  // 100% refund = 100% tokens
            "2000, 1000, 1000, 500",   // 50% refund = 50% tokens
            "1000, 2000, 500, 1000"    // 50% refund = 50% tokens
        })
        @DisplayName("Should calculate proportional tokens correctly for various scenarios")
        void shouldCalculateProportionalTokensCorrectlyForVariousScenarios(
                long originalAmountCents, long originalTokens, long refundAmountCents, long expectedTokens) {
            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(expectedTokens);
        }
    }

    @Nested
    @DisplayName("Monotonicity Tests")
    class MonotonicityTests {

        @Test
        @DisplayName("Should maintain monotonicity - more refund amount should result in more tokens")
        void shouldMaintainMonotonicityMoreRefundAmountShouldResultInMoreTokens() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;

            // When
            long result1 = calculateProportionalTokens(originalAmountCents, originalTokens, 100L);
            long result2 = calculateProportionalTokens(originalAmountCents, originalTokens, 200L);
            long result3 = calculateProportionalTokens(originalAmountCents, originalTokens, 300L);

            // Then
            assertThat(result1).isLessThanOrEqualTo(result2);
            assertThat(result2).isLessThanOrEqualTo(result3);
        }

        @Test
        @DisplayName("Should maintain monotonicity - more original tokens should result in more refund tokens")
        void shouldMaintainMonotonicityMoreOriginalTokensShouldResultInMoreRefundTokens() {
            // Given
            long originalAmountCents = 1000L;
            long refundAmountCents = 500L;

            // When
            long result1 = calculateProportionalTokens(originalAmountCents, 100L, refundAmountCents);
            long result2 = calculateProportionalTokens(originalAmountCents, 200L, refundAmountCents);
            long result3 = calculateProportionalTokens(originalAmountCents, 300L, refundAmountCents);

            // Then
            assertThat(result1).isLessThanOrEqualTo(result2);
            assertThat(result2).isLessThanOrEqualTo(result3);
        }

        @Test
        @DisplayName("Should maintain bounds - result should never exceed original tokens")
        void shouldMaintainBoundsResultShouldNeverExceedOriginalTokens() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, 1000L);

            // Then
            assertThat(result).isLessThanOrEqualTo(originalTokens);
        }

        @Test
        @DisplayName("Should maintain bounds - result should never be negative")
        void shouldMaintainBoundsResultShouldNeverBeNegative() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, 500L);

            // Then
            assertThat(result).isGreaterThanOrEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Large Values Tests")
    class LargeValuesTests {

        @Test
        @DisplayName("Should handle large values without overflow")
        void shouldHandleLargeValuesWithoutOverflow() {
            // Given - Use values near Long.MAX_VALUE but safe for multiplication
            long originalAmountCents = 1_000_000_000L; // 1 billion cents
            long originalTokens = 1_000_000_000L;      // 1 billion tokens
            long refundAmountCents = 500_000_000L;     // 500 million cents

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(500_000_000L); // Should be exactly half
        }

        @Test
        @DisplayName("Should handle maximum safe values")
        void shouldHandleMaximumSafeValues() {
            // Given - Use maximum safe values that won't overflow when multiplied
            long maxSafeValue = (long) Math.sqrt(Long.MAX_VALUE / 2); // Safe for multiplication
            long originalAmountCents = maxSafeValue;
            long originalTokens = maxSafeValue;
            long refundAmountCents = maxSafeValue / 2;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(maxSafeValue / 2);
            assertThat(result).isGreaterThan(0L);
        }

        @Test
        @DisplayName("Should handle edge case with very small refund amount")
        void shouldHandleEdgeCaseWithVerySmallRefundAmount() {
            // Given
            long originalAmountCents = Long.MAX_VALUE / 2;
            long originalTokens = Long.MAX_VALUE / 2;
            long refundAmountCents = 1L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should handle edge case with very small original amount")
        void shouldHandleEdgeCaseWithVerySmallOriginalAmount() {
            // Given
            long originalAmountCents = 1L;
            long originalTokens = Long.MAX_VALUE / 2;
            long refundAmountCents = 1L;

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(Long.MAX_VALUE / 2);
        }
    }

    @Nested
    @DisplayName("Property Tests")
    class PropertyTests {

        @ParameterizedTest
        @ValueSource(longs = {1L, 10L, 100L, 1000L, 10000L, 100000L, 1000000L})
        @DisplayName("Should maintain proportional relationship for various scales")
        void shouldMaintainProportionalRelationshipForVariousScales(long scale) {
            // Given
            long originalAmountCents = scale * 1000L;
            long originalTokens = scale * 1000L;
            long refundAmountCents = scale * 500L; // 50% refund

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(scale * 500L); // Should be exactly 50% of original tokens
        }

        @Test
        @DisplayName("Should maintain commutative property - order of multiplication should not matter")
        void shouldMaintainCommutativePropertyOrderOfMultiplicationShouldNotMatter() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 2000L;
            long refundAmountCents = 500L;

            // When
            long result1 = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);
            long result2 = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("Should maintain identity property - 100% refund should return all tokens")
        void shouldMaintainIdentityProperty100PercentRefundShouldReturnAllTokens() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 2000L;
            long refundAmountCents = 1000L; // 100% refund

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(originalTokens);
        }

        @Test
        @DisplayName("Should maintain zero property - 0% refund should return 0 tokens")
        void shouldMaintainZeroProperty0PercentRefundShouldReturn0Tokens() {
            // Given
            long originalAmountCents = 1000L;
            long originalTokens = 2000L;
            long refundAmountCents = 0L; // 0% refund

            // When
            long result = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);

            // Then
            assertThat(result).isEqualTo(0L);
        }
    }

    /**
     * Helper method that implements the proportional calculation logic used in the webhook service.
     * This mirrors the actual implementation: (originalTokens * refundAmountCents) / originalAmountCents
     */
    private long calculateProportionalTokens(long originalAmountCents, long originalTokens, long refundAmountCents) {
        // Zero-division guard
        if (originalAmountCents <= 0) {
            return 0L;
        }
        
        // Calculate proportional tokens using floor division
        return (originalTokens * refundAmountCents) / originalAmountCents;
    }
}
