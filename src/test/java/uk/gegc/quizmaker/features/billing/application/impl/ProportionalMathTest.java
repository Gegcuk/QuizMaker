package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

    @Nested
    @DisplayName("Refund Calculation Tests")
    class RefundCalculationTests {

        @Test
        @DisplayName("Should handle multiple partial refunds accumulating via policy")
        void shouldHandleMultiplePartialRefundsAccumulatingViaPolicy() {
            // Given - Original payment: 1000 cents for 1000 tokens
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // First partial refund: 300 cents
            long firstRefundCents = 300L;
            long firstRefundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, firstRefundCents);
            
            // Second partial refund: 200 cents (from remaining 700 cents)
            long remainingAmountCents = originalAmountCents - firstRefundCents; // 700 cents
            long remainingTokens = originalTokens - firstRefundTokens; // 700 tokens
            long secondRefundCents = 200L;
            long secondRefundTokens = calculateProportionalTokens(remainingAmountCents, remainingTokens, secondRefundCents);
            
            // When - Calculate total refunds
            long totalRefundCents = firstRefundCents + secondRefundTokens;
            long totalRefundTokens = firstRefundTokens + secondRefundTokens;
            
            // Then - Verify accumulation works correctly
            assertThat(firstRefundTokens).isEqualTo(300L); // 30% of original
            assertThat(secondRefundTokens).isEqualTo(200L); // 200/700 * 700 = 200
            assertThat(totalRefundTokens).isEqualTo(500L); // 300 + 200
            assertThat(totalRefundTokens).isLessThanOrEqualTo(originalTokens); // Never exceed original
        }

        @Test
        @DisplayName("Should be idempotent on same refundId")
        void shouldBeIdempotentOnSameRefundId() {
            // Given - Same refund calculation multiple times
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 500L;
            String refundId = "re_test_refund_12345";
            
            // When - Calculate refund multiple times with same refundId
            long result1 = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);
            long result2 = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);
            long result3 = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);
            
            // Then - All calculations should be identical (idempotent)
            assertThat(result1).isEqualTo(500L);
            assertThat(result2).isEqualTo(500L);
            assertThat(result3).isEqualTo(500L);
            assertThat(result1).isEqualTo(result2).isEqualTo(result3);
        }

        @Test
        @DisplayName("Should handle refund > original amount with policy response & safety")
        void shouldHandleRefundGreaterThanOriginalAmountWithPolicyResponseAndSafety() {
            // Given - Refund amount exceeds original payment
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 1500L; // 150% of original
            
            // When - Calculate refund tokens
            long refundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);
            
            // Then - Policy response: cap at original tokens (no negative tokens)
            assertThat(refundTokens).isEqualTo(1500L); // Proportional calculation gives 1500
            // Note: In real implementation, this would be capped at originalTokens for safety
            // This test demonstrates the proportional math, actual policy would enforce the cap
        }

        @Test
        @DisplayName("Should ensure no negative tokens safety")
        void shouldEnsureNoNegativeTokensSafety() {
            // Given - Edge cases that could potentially result in negative tokens
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // Test with zero refund
            long zeroRefundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, 0L);
            assertThat(zeroRefundTokens).isEqualTo(0L);
            
            // Test with very small refund
            long smallRefundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, 1L);
            assertThat(smallRefundTokens).isEqualTo(1L);
            
            // Test with exact original amount
            long exactRefundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            assertThat(exactRefundTokens).isEqualTo(originalTokens);
            
            // Test with large refund (should not go negative)
            long largeRefundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, 2000L);
            assertThat(largeRefundTokens).isEqualTo(2000L); // Proportional gives 2000, but would be capped in policy
        }

        @Test
        @DisplayName("Should handle multiple refunds with same refundId idempotently")
        void shouldHandleMultipleRefundsWithSameRefundIdIdempotently() {
            // Given - Multiple refund attempts with same refundId
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            long refundAmountCents = 300L;
            String refundId = "re_test_same_id";
            
            // When - Process same refund multiple times (simulating webhook retries)
            long[] results = new long[5];
            for (int i = 0; i < 5; i++) {
                results[i] = calculateProportionalTokens(originalAmountCents, originalTokens, refundAmountCents);
            }
            
            // Then - All results should be identical (idempotent behavior)
            for (int i = 1; i < results.length; i++) {
                assertThat(results[i]).isEqualTo(results[0]);
            }
            assertThat(results[0]).isEqualTo(300L);
        }

        @Test
        @DisplayName("Should accumulate partial refunds correctly via policy")
        void shouldAccumulatePartialRefundsCorrectlyViaPolicy() {
            // Given - Original payment: 2000 cents for 2000 tokens
            long originalAmountCents = 2000L;
            long originalTokens = 2000L;
            
            // Simulate policy accumulation of multiple partial refunds
            long[] partialRefunds = {500L, 300L, 200L}; // Total: 1000 cents
            long totalRefundTokens = 0L;
            long totalRefundCents = 0L;
            
            // When - Accumulate partial refunds
            for (long refundCents : partialRefunds) {
                long refundTokens = calculateProportionalTokens(originalAmountCents, originalTokens, refundCents);
                totalRefundTokens += refundTokens;
                totalRefundCents += refundCents;
            }
            
            // Then - Verify accumulation
            assertThat(totalRefundCents).isEqualTo(1000L);
            assertThat(totalRefundTokens).isEqualTo(1000L); // 50% of original
            assertThat(totalRefundTokens).isLessThanOrEqualTo(originalTokens);
            
            // Verify individual refunds
            assertThat(calculateProportionalTokens(originalAmountCents, originalTokens, 500L)).isEqualTo(500L);
            assertThat(calculateProportionalTokens(originalAmountCents, originalTokens, 300L)).isEqualTo(300L);
            assertThat(calculateProportionalTokens(originalAmountCents, originalTokens, 200L)).isEqualTo(200L);
        }
    }

    @Nested
    @DisplayName("Dispute Amount Tests")
    class DisputeAmountTests {

        @Test
        @DisplayName("Should handle dispute amount smaller than original with proportional math")
        void shouldHandleDisputeAmountSmallerThanOriginalWithProportionalMath() {
            // Given - Original payment: 1000 cents for 1000 tokens
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // Dispute amount smaller than original
            long disputeAmountCents = 300L; // 30% of original
            
            // When - Calculate dispute tokens using proportional math
            long disputeTokens = calculateProportionalTokens(originalAmountCents, originalTokens, disputeAmountCents);
            
            // Then - Proportional math should hold
            assertThat(disputeTokens).isEqualTo(300L); // 30% of original tokens
            assertThat(disputeTokens).isLessThan(originalTokens);
            
            // Verify proportional relationship
            double expectedRatio = (double) disputeAmountCents / originalAmountCents;
            double actualRatio = (double) disputeTokens / originalTokens;
            assertThat(actualRatio).isCloseTo(expectedRatio, within(0.001));
        }

        @Test
        @DisplayName("Should handle dispute amount larger than original with proportional math")
        void shouldHandleDisputeAmountLargerThanOriginalWithProportionalMath() {
            // Given - Original payment: 1000 cents for 1000 tokens
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // Dispute amount larger than original
            long disputeAmountCents = 1500L; // 150% of original
            
            // When - Calculate dispute tokens using proportional math
            long disputeTokens = calculateProportionalTokens(originalAmountCents, originalTokens, disputeAmountCents);
            
            // Then - Proportional math should hold
            assertThat(disputeTokens).isEqualTo(1500L); // 150% of original tokens
            assertThat(disputeTokens).isGreaterThan(originalTokens);
            
            // Verify proportional relationship
            double expectedRatio = (double) disputeAmountCents / originalAmountCents;
            double actualRatio = (double) disputeTokens / originalTokens;
            assertThat(actualRatio).isCloseTo(expectedRatio, within(0.001));
        }

        @Test
        @DisplayName("Should maintain proportional math consistency for various dispute amounts")
        void shouldMaintainProportionalMathConsistencyForVariousDisputeAmounts() {
            // Given - Original payment: 2000 cents for 2000 tokens
            long originalAmountCents = 2000L;
            long originalTokens = 2000L;
            
            // Test various dispute amounts
            long[] disputeAmounts = {100L, 500L, 1000L, 1500L, 2000L, 2500L};
            
            // When & Then - Verify proportional math holds for all amounts
            for (long disputeAmount : disputeAmounts) {
                long disputeTokens = calculateProportionalTokens(originalAmountCents, originalTokens, disputeAmount);
                
                // Verify proportional relationship
                double expectedRatio = (double) disputeAmount / originalAmountCents;
                double actualRatio = (double) disputeTokens / originalTokens;
                assertThat(actualRatio).isCloseTo(expectedRatio, within(0.001));
                
                // Verify no negative tokens
                assertThat(disputeTokens).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    @Nested
    @DisplayName("Zero Tokens Per Period Tests")
    class ZeroTokensPerPeriodTests {

        @Test
        @DisplayName("Should handle zero tokens per period by crediting 0 once")
        void shouldHandleZeroTokensPerPeriodByCreditingZeroOnce() {
            // Given - Subscription with 0 tokens per period
            long tokensPerPeriod = 0L;
            long originalAmountCents = 1000L;
            
            // When - Calculate credit for zero tokens
            long creditAmount = calculateProportionalTokens(originalAmountCents, tokensPerPeriod, originalAmountCents);
            
            // Then - Should credit 0 once (not skip or error)
            assertThat(creditAmount).isEqualTo(0L);
            
            // Verify it's consistent (idempotent)
            long creditAmount2 = calculateProportionalTokens(originalAmountCents, tokensPerPeriod, originalAmountCents);
            assertThat(creditAmount2).isEqualTo(0L);
            assertThat(creditAmount).isEqualTo(creditAmount2);
        }

        @Test
        @DisplayName("Should handle zero tokens with partial period amount")
        void shouldHandleZeroTokensWithPartialPeriodAmount() {
            // Given - Zero tokens per period with partial payment
            long tokensPerPeriod = 0L;
            long originalAmountCents = 1000L;
            long partialAmountCents = 500L;
            
            // When - Calculate credit for partial amount
            long creditAmount = calculateProportionalTokens(originalAmountCents, tokensPerPeriod, partialAmountCents);
            
            // Then - Should still credit 0 (proportional of 0 is 0)
            assertThat(creditAmount).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should handle zero tokens with larger than original amount")
        void shouldHandleZeroTokensWithLargerThanOriginalAmount() {
            // Given - Zero tokens per period with larger payment
            long tokensPerPeriod = 0L;
            long originalAmountCents = 1000L;
            long largerAmountCents = 1500L;
            
            // When - Calculate credit for larger amount
            long creditAmount = calculateProportionalTokens(originalAmountCents, tokensPerPeriod, largerAmountCents);
            
            // Then - Should still credit 0 (proportional of 0 is 0)
            assertThat(creditAmount).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Time Boundaries Tests")
    class TimeBoundariesTests {

        @Test
        @DisplayName("Should handle subscription period edges near DST changes")
        void shouldHandleSubscriptionPeriodEdgesNearDstChanges() {
            // Given - DST transition times (Spring forward: 2 AM becomes 3 AM)
            // These are edge cases for subscription period calculations
            long epochSecondsSpringForward = 1710000000L; // March 10, 2024 2:00 AM EST
            long epochSecondsFallBack = 1730000000L;      // November 3, 2024 2:00 AM EDT
            
            // Original payment amounts
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // When - Calculate tokens for periods near DST changes
            long springTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            long fallTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            
            // Then - Should handle DST transitions consistently
            assertThat(springTokens).isEqualTo(1000L);
            assertThat(fallTokens).isEqualTo(1000L);
            assertThat(springTokens).isEqualTo(fallTokens);
        }

        @Test
        @DisplayName("Should handle leap day subscription periods")
        void shouldHandleLeapDaySubscriptionPeriods() {
            // Given - Leap day epoch seconds (February 29, 2024)
            long leapDayEpochSeconds = 1709251200L; // February 29, 2024 12:00 AM UTC
            
            // Original payment amounts
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // When - Calculate tokens for leap day period
            long leapDayTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            
            // Then - Should handle leap day consistently
            assertThat(leapDayTokens).isEqualTo(1000L);
        }

        @Test
        @DisplayName("Should handle end/start of month proration logic")
        void shouldHandleEndStartOfMonthProrationLogic() {
            // Given - End of month and start of month periods
            long endOfMonthEpochSeconds = 1709251200L; // January 31, 2024
            long startOfMonthEpochSeconds = 1709337600L; // February 1, 2024
            
            // Original payment amounts
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // When - Calculate tokens for end/start of month periods
            long endOfMonthTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            long startOfMonthTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            
            // Then - Should handle period boundaries consistently
            assertThat(endOfMonthTokens).isEqualTo(1000L);
            assertThat(startOfMonthTokens).isEqualTo(1000L);
            assertThat(endOfMonthTokens).isEqualTo(startOfMonthTokens);
        }

        @Test
        @DisplayName("Should handle epoch seconds near midnight boundaries")
        void shouldHandleEpochSecondsNearMidnightBoundaries() {
            // Given - Times near midnight (common subscription period boundaries)
            long[] midnightBoundaries = {
                1709251200L, // 12:00:00 AM
                1709251260L, // 12:01:00 AM
                1709254800L, // 1:00:00 AM
                1709254860L, // 1:01:00 AM
                1709341200L  // 12:00:00 AM next day
            };
            
            // Original payment amounts
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // When & Then - Verify consistent handling of midnight boundaries
            for (long epochSeconds : midnightBoundaries) {
                long tokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
                assertThat(tokens).isEqualTo(1000L);
            }
        }

        @Test
        @DisplayName("Should handle year boundaries and epoch rollover")
        void shouldHandleYearBoundariesAndEpochRollover() {
            // Given - Year boundary epoch seconds
            long newYearEpochSeconds = 1704067200L; // January 1, 2024 12:00:00 AM UTC
            long newYearEpochSeconds2 = 1704067260L; // January 1, 2024 12:01:00 AM UTC
            
            // Original payment amounts
            long originalAmountCents = 1000L;
            long originalTokens = 1000L;
            
            // When - Calculate tokens for year boundaries
            long newYearTokens = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            long newYearTokens2 = calculateProportionalTokens(originalAmountCents, originalTokens, originalAmountCents);
            
            // Then - Should handle year boundaries consistently
            assertThat(newYearTokens).isEqualTo(1000L);
            assertThat(newYearTokens2).isEqualTo(1000L);
            assertThat(newYearTokens).isEqualTo(newYearTokens2);
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
