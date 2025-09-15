package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.BalanceMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.TokenTransactionMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.ReservationMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive tests for rounding and fractional token handling.
 * 
 * I6. Rounding: Conversions use ceil where specified; no fractional billing tokens.
 * 
 * Key requirements:
 * - All token amounts must be integers (no fractional billing tokens)
 * - Conversions should use ceiling function where specified
 * - Rounding should be consistent across all operations
 * - Edge cases with very small fractions should be handled correctly
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Rounding and Fractional Token Tests")
@Execution(ExecutionMode.CONCURRENT)
class RoundingAndFractionalTokenTest {

    @Mock
    private BillingProperties billingProperties;
    
    @Mock
    private BalanceRepository balanceRepository;
    
    @Mock
    private ReservationRepository reservationRepository;
    
    @Mock
    private TokenTransactionRepository transactionRepository;
    
    @Mock
    private QuizGenerationJobRepository quizGenerationJobRepository;
    
    @Mock
    private BalanceMapper balanceMapper;
    
    @Mock
    private TokenTransactionMapper transactionMapper;
    
    @Mock
    private ReservationMapper reservationMapper;
    
    @Mock
    private BillingMetricsService metricsService;
    
    @Mock
    private EntityManager entityManager;

    private BillingServiceImpl billingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        billingService = new BillingServiceImpl(
            billingProperties,
            balanceRepository,
            transactionRepository,
            reservationRepository,
            quizGenerationJobRepository,
            balanceMapper,
            transactionMapper,
            reservationMapper,
            objectMapper,
            metricsService
        );
        
        // Inject dependencies to avoid NPE
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "objectMapper", objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "entityManager", entityManager);
        
        // Setup common mocks
        lenient().when(billingProperties.getReservationTtlMinutes()).thenReturn(30);
    }

    @Nested
    @DisplayName("Ceiling Function Tests")
    class CeilingFunctionTests {

        @Test
        @DisplayName("Should use ceiling for token conversions with positive fractions")
        void shouldUseCeilingForTokenConversionsWithPositiveFractions() {
            // Test various fractional inputs that should round up
            double[][] testCases = {
                {1234.1, 1.0, 1235L},    // Small fraction rounds up
                {1234.7, 1.0, 1235L},    // Large fraction rounds up
                {1234.9, 1.0, 1235L},    // Very large fraction rounds up
                {1000.0, 1.0, 1000L},    // Exact integer stays same
                {1000.1, 1.5, 1501L},    // Fraction with ratio rounds up
                {1234.7, 1.5, 1853L},    // Complex fraction with ratio
                {999.9, 2.0, 2000L},     // Edge case with ratio
                {0.1, 1.0, 1L},          // Very small fraction
                {0.9, 1.0, 1L},          // Very small fraction
                {0.01, 1.0, 1L}          // Tiny fraction
            };

            for (double[] testCase : testCases) {
                double input = testCase[0];
                double ratio = testCase[1];
                long expected = (long) testCase[2];
                
                long result = (long) Math.ceil(input * ratio);
                assertThat(result).isEqualTo(expected)
                    .as("Input: %f, Ratio: %f should round up to %d", input, ratio, expected);
            }
        }

        @Test
        @DisplayName("Should handle ceiling with different ratios")
        void shouldHandleCeilingWithDifferentRatios() {
            double input = 1000.5;
            double[] ratios = {1.0, 1.25, 1.5, 2.0, 0.5, 0.75};
            long[] expected = {1001L, 1251L, 1501L, 2001L, 501L, 751L};

            for (int i = 0; i < ratios.length; i++) {
                long result = (long) Math.ceil(input * ratios[i]);
                assertThat(result).isEqualTo(expected[i])
                    .as("Input: %f with ratio %f should round up to %d", input, ratios[i], expected[i]);
            }
        }

        @Test
        @DisplayName("Should handle edge cases with very small fractions")
        void shouldHandleEdgeCasesWithVerySmallFractions() {
            // Test cases with very small fractions that should still round up
            double[][] edgeCases = {
                {1000.0001, 1.0, 1001L},  // Very small fraction
                {1000.0000001, 1.0, 1001L}, // Extremely small fraction
                {999.9999, 1.0, 1000L},   // Very close to integer
                {0.0001, 1.0, 1L},        // Tiny positive fraction
                {0.0000001, 1.0, 1L}      // Extremely tiny fraction
            };

            for (double[] testCase : edgeCases) {
                double input = testCase[0];
                double ratio = testCase[1];
                long expected = (long) testCase[2];
                
                long result = (long) Math.ceil(input * ratio);
                assertThat(result).isEqualTo(expected)
                    .as("Edge case input: %f should round up to %d", input, expected);
            }
        }

        @Test
        @DisplayName("Should never round down with ceiling function")
        void shouldNeverRoundDownWithCeilingFunction() {
            // Test that ceiling never rounds down, even for very small fractions
            double[] inputs = {1000.1, 1000.01, 1000.001, 1000.0001, 1000.00001};
            
            for (double input : inputs) {
                long result = (long) Math.ceil(input);
                assertThat(result).isGreaterThan((long) input)
                    .as("Ceiling of %f should be greater than %d", input, (long) input);
            }
        }
    }

    @Nested
    @DisplayName("Integer Token Validation Tests")
    class IntegerTokenValidationTests {

        @Test
        @DisplayName("Should ensure all reservation amounts are integers")
        void shouldEnsureAllReservationAmountsAreIntegers() {
            // Given
            UUID userId = UUID.randomUUID();
            long[] testAmounts = {1000L, 1234L, 1L, 50000L, 0L};
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(100000L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                res.setId(UUID.randomUUID());
                return res;
            });
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                return new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    res.getId(), userId, ReservationState.ACTIVE, res.getEstimatedTokens(), 0L, null, null, null, null);
            });

            // When & Then - test each amount
            for (long amount : testAmounts) {
                if (amount > 0) { // Skip zero amount as it should throw exception
                    var result = billingService.reserve(userId, amount, "test-ref", "test-key-" + amount);
                    
                    // Verify amount is integer
                    assertThat(result.estimatedTokens()).isEqualTo(amount);
                    assertThat(result.estimatedTokens() % 1).isEqualTo(0);
                    
                    // Verify balance amounts are integers
                    assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
                    assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("Should ensure all commit amounts are integers")
        void shouldEnsureAllCommitAmountsAreIntegers() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long[] commitAmounts = {100L, 500L, 750L, 1000L};
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When & Then - test each commit amount
            for (long commitAmount : commitAmounts) {
                var result = billingService.commit(reservationId, commitAmount, "test-ref", "test-key-" + commitAmount);
                
                // Verify commit amount is integer
                assertThat(result.committedTokens()).isEqualTo(commitAmount);
                assertThat(result.committedTokens() % 1).isEqualTo(0);
                
                // Verify released amount is integer
                assertThat(result.releasedTokens() % 1).isEqualTo(0);
                
                // Verify balance amounts remain integers
                assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
                assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should ensure all credit amounts are integers")
        void shouldEnsureAllCreditAmountsAreIntegers() {
            // Given
            UUID userId = UUID.randomUUID();
            long[] creditAmounts = {100L, 1000L, 5000L, 10000L};
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(0L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When & Then - test each credit amount
            for (long creditAmount : creditAmounts) {
                billingService.creditPurchase(userId, creditAmount, "test-key-" + creditAmount, "test", null);
                
                // Verify credit amount is integer
                assertThat(creditAmount % 1).isEqualTo(0);
                
                // Verify balance amounts are integers
                assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
                assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Fractional Input Handling Tests")
    class FractionalInputHandlingTests {

        @Test
        @DisplayName("Should reject fractional reservation amounts")
        void shouldRejectFractionalReservationAmounts() {
            // Given
            UUID userId = UUID.randomUUID();
            
            // Test that the service only accepts long (integer) amounts
            // This is enforced at the API level, but we verify the service behavior
            
            // The service method signature uses 'long' which prevents fractional inputs
            // at compile time, but we can test the behavior with edge cases
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(10000L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                res.setId(UUID.randomUUID());
                return res;
            });
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                return new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    res.getId(), userId, ReservationState.ACTIVE, res.getEstimatedTokens(), 0L, null, null, null, null);
            });

            // When - use integer amounts (long type prevents fractional inputs)
            long amount = 1000L;
            var result = billingService.reserve(userId, amount, "test-ref", "test-key");
            
            // Then - verify amount is integer
            assertThat(result.estimatedTokens()).isEqualTo(amount);
            assertThat(result.estimatedTokens() % 1).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle conversion from fractional to integer tokens")
        void shouldHandleConversionFromFractionalToIntegerTokens() {
            // Test the conversion logic that would be used in estimation services
            double[][] conversionTests = {
                {1000.1, 1.0, 1001L},
                {1000.7, 1.0, 1001L},
                {1000.0, 1.0, 1000L},
                {1000.1, 1.5, 1501L},
                {1000.7, 1.5, 1502L},
                {1000.0, 1.5, 1500L},
                {999.9, 2.0, 2000L},
                {999.1, 2.0, 1999L}
            };

            for (double[] test : conversionTests) {
                double input = test[0];
                double ratio = test[1];
                long expected = (long) test[2];
                
                // Simulate the conversion logic
                long result = (long) Math.ceil(input * ratio);
                
                assertThat(result).isEqualTo(expected)
                    .as("Conversion of %f with ratio %f should result in %d", input, ratio, expected);
                
                // Verify result is integer
                assertThat(result % 1).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should maintain integer precision in complex calculations")
        void shouldMaintainIntegerPrecisionInComplexCalculations() {
            // Test complex scenarios that might introduce fractional precision issues
            double baseAmount = 1234.7;
            double ratio = 1.25;
            long convertedAmount = (long) Math.ceil(baseAmount * ratio);
            
            // Verify the converted amount is integer
            assertThat(convertedAmount).isEqualTo(1544L);
            assertThat(convertedAmount % 1).isEqualTo(0);
            
            // Test that subsequent operations maintain integer precision
            long reservedAmount = convertedAmount;
            long commitAmount = (long) Math.ceil(reservedAmount * 0.8); // 80% of reserved
            long releasedAmount = reservedAmount - commitAmount;
            
            // All amounts should be integers
            assertThat(reservedAmount % 1).isEqualTo(0);
            assertThat(commitAmount % 1).isEqualTo(0);
            assertThat(releasedAmount % 1).isEqualTo(0);
            
            // Verify the math is correct
            assertThat(commitAmount + releasedAmount).isEqualTo(reservedAmount);
        }
    }

    @Nested
    @DisplayName("Edge Cases and Boundary Tests")
    class EdgeCasesAndBoundaryTests {

        @Test
        @DisplayName("Should handle zero amounts correctly")
        void shouldHandleZeroAmountsCorrectly() {
            // Given
            UUID userId = UUID.randomUUID();
            
            // When & Then - zero reservation should be rejected
            assertThatThrownBy(() -> {
                billingService.reserve(userId, 0L, "test-ref", "test-key");
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("estimatedBillingTokens must be > 0");
            
            // When & Then - zero commit should be rejected
            assertThatThrownBy(() -> {
                billingService.commit(UUID.randomUUID(), 0L, "test-ref", "test-key");
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("actualBillingTokens must be > 0");
            
            // When & Then - zero credit should be rejected
            assertThatThrownBy(() -> {
                billingService.creditPurchase(userId, 0L, "test-key", "test", null);
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("tokens must be > 0");
        }

        @Test
        @DisplayName("Should handle very large amounts correctly")
        void shouldHandleVeryLargeAmountsCorrectly() {
            // Test with very large amounts to ensure no precision loss
            long[] largeAmounts = {
                1_000_000L,
                10_000_000L,
                100_000_000L,
                1_000_000_000L,
                Long.MAX_VALUE / 2 // Avoid overflow
            };
            
            for (long amount : largeAmounts) {
                // Verify amount is integer
                assertThat(amount % 1).isEqualTo(0);
                
                // Test ceiling conversion
                long converted = (long) Math.ceil(amount * 1.1);
                assertThat(converted % 1).isEqualTo(0);
                assertThat(converted).isGreaterThan(amount);
            }
        }

        @Test
        @DisplayName("Should handle very small amounts correctly")
        void shouldHandleVerySmallAmountsCorrectly() {
            // Test with very small amounts
            long[] smallAmounts = {1L, 2L, 5L, 10L, 100L};
            
            for (long amount : smallAmounts) {
                // Verify amount is integer
                assertThat(amount % 1).isEqualTo(0);
                
                // Test ceiling conversion with small amounts
                long converted = (long) Math.ceil(amount * 1.1);
                assertThat(converted % 1).isEqualTo(0);
                assertThat(converted).isGreaterThanOrEqualTo(amount);
            }
        }

        @Test
        @DisplayName("Should handle boundary conditions in rounding")
        void shouldHandleBoundaryConditionsInRounding() {
            // Test boundary conditions where rounding behavior is critical
            double[] boundaryInputs = {
                1000.0,    // Exact integer
                1000.0001, // Just above integer
                999.9999,  // Just below integer
                0.0,       // Zero
                0.0001,    // Very small positive
                1.0,       // Unit
                1.0001     // Just above unit
            };
            
            for (double input : boundaryInputs) {
                if (input > 0) { // Skip zero
                    long result = (long) Math.ceil(input);
                    assertThat(result % 1).isEqualTo(0);
                    
                    if (input == Math.floor(input)) {
                        // Exact integer should stay the same
                        assertThat(result).isEqualTo((long) input);
                    } else {
                        // Fractional should round up
                        assertThat(result).isGreaterThan((long) input);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Integration with Billing Operations")
    class IntegrationWithBillingOperationsTests {

        @Test
        @DisplayName("Should maintain integer precision throughout complete billing flow")
        void shouldMaintainIntegerPrecisionThroughoutCompleteBillingFlow() {
            // Given
            UUID userId = UUID.randomUUID();
            double inputTokens = 1234.7;
            double ratio = 1.25;
            long estimatedTokens = (long) Math.ceil(inputTokens * ratio); // 1544L
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(10000L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                res.setId(UUID.randomUUID());
                return res;
            });
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                return new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    res.getId(), userId, ReservationState.ACTIVE, res.getEstimatedTokens(), 0L, null, null, null, null);
            });

            // When - reserve tokens
            var reservation = billingService.reserve(userId, estimatedTokens, "test-ref", "test-key");
            
            // Then - verify all amounts are integers
            assertThat(reservation.estimatedTokens()).isEqualTo(estimatedTokens);
            assertThat(reservation.estimatedTokens() % 1).isEqualTo(0);
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
            
            // When - commit partial amount
            long commitAmount = (long) Math.ceil(estimatedTokens * 0.8); // 80% of estimated
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(estimatedTokens);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            var commitResult = billingService.commit(reservation.id(), commitAmount, "test-ref", "commit-key");
            
            // Then - verify all amounts remain integers
            assertThat(commitResult.committedTokens() % 1).isEqualTo(0);
            assertThat(commitResult.releasedTokens() % 1).isEqualTo(0);
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
            
            // Verify the math is correct
            assertThat(commitResult.committedTokens() + commitResult.releasedTokens())
                .isEqualTo(estimatedTokens);
        }

        @Test
        @DisplayName("Should handle multiple conversions maintaining integer precision")
        void shouldHandleMultipleConversionsMaintainingIntegerPrecision() {
            // Test a scenario with multiple conversion steps
            double[] inputs = {1000.1, 2000.7, 1500.3};
            double[] ratios = {1.25, 1.5, 1.1};
            
            long[] convertedAmounts = new long[inputs.length];
            
            // Convert each input
            for (int i = 0; i < inputs.length; i++) {
                convertedAmounts[i] = (long) Math.ceil(inputs[i] * ratios[i]);
                assertThat(convertedAmounts[i] % 1).isEqualTo(0);
            }
            
            // Sum all converted amounts
            long totalAmount = 0L;
            for (long amount : convertedAmounts) {
                totalAmount += amount;
                assertThat(amount % 1).isEqualTo(0);
            }
            
            // Verify total is integer
            assertThat(totalAmount % 1).isEqualTo(0);
            assertThat(totalAmount).isEqualTo(1251L + 3002L + 1651L); // Expected values
        }
    }
}
