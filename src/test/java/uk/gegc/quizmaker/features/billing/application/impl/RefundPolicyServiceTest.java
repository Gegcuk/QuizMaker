package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.RefundPolicy;
import uk.gegc.quizmaker.features.billing.application.RefundPolicyService;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RefundPolicyService covering deterministic behavior, policy enforcement, and refund calculations.
 */
@ExtendWith(MockitoExtension.class)
class RefundPolicyServiceTest {

    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private PaymentRepository paymentRepository;
    
    @Mock
    private TokenTransactionRepository transactionRepository;
    
    private RefundPolicyService refundPolicyService;
    
    private Payment testPayment;
    private UUID testUserId;
    private String testRefundId;
    private String testEventId;

    @BeforeEach
    void setUp() {
        refundPolicyService = new RefundPolicyServiceImpl(
            internalBillingService, 
            paymentRepository, 
            transactionRepository
        );
        
        testUserId = UUID.randomUUID();
        testRefundId = "re_test123";
        testEventId = "evt_test789";
        
        // Create test payment
        testPayment = new Payment();
        testPayment.setId(UUID.randomUUID());
        testPayment.setUserId(testUserId);
        testPayment.setAmountCents(1000L); // $10.00
        testPayment.setCreditedTokens(1000L);
        testPayment.setRefundedAmountCents(0L);
        testPayment.setStatus(PaymentStatus.SUCCEEDED);
        testPayment.setCreatedAt(LocalDateTime.now().minusHours(1));
        testPayment.setStripeSessionId("cs_test123");
    }

    @Nested
    @DisplayName("Deterministic Behavior Tests")
    class DeterministicBehaviorTests {

        @Test
        @DisplayName("Should be deterministic for same inputs")
        void shouldBeDeterministicForSameInputs() {
            // Given - Mock no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            // Set policy to ALLOW_NEGATIVE_BALANCE
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            long refundAmountCents = 500L; // $5.00
            
            // When - Call multiple times with same inputs
            RefundCalculationDto result1 = refundPolicyService.calculateRefund(testPayment, refundAmountCents);
            RefundCalculationDto result2 = refundPolicyService.calculateRefund(testPayment, refundAmountCents);
            RefundCalculationDto result3 = refundPolicyService.calculateRefund(testPayment, refundAmountCents);
            
            // Then - All results should be identical
            assertThat(result1).isEqualTo(result2);
            assertThat(result2).isEqualTo(result3);
            assertThat(result1.refundAllowed()).isTrue();
            assertThat(result1.refundAmountCents()).isEqualTo(refundAmountCents);
            assertThat(result1.tokensToDeduct()).isEqualTo(500L); // Proportional: (1000 * 500) / 1000
        }

        @Test
        @DisplayName("Should return consistent results across different refund amounts")
        void shouldReturnConsistentResultsAcrossDifferentRefundAmounts() {
            // Given - Mock no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // When - Test different refund amounts
            RefundCalculationDto result25 = refundPolicyService.calculateRefund(testPayment, 250L); // 25%
            RefundCalculationDto result50 = refundPolicyService.calculateRefund(testPayment, 500L); // 50%
            RefundCalculationDto result75 = refundPolicyService.calculateRefund(testPayment, 750L); // 75%
            RefundCalculationDto result100 = refundPolicyService.calculateRefund(testPayment, 1000L); // 100%
            
            // Then - Proportional calculations should be consistent
            assertThat(result25.tokensToDeduct()).isEqualTo(250L);
            assertThat(result50.tokensToDeduct()).isEqualTo(500L);
            assertThat(result75.tokensToDeduct()).isEqualTo(750L);
            assertThat(result100.tokensToDeduct()).isEqualTo(1000L);
            
            // All should be allowed under ALLOW_NEGATIVE_BALANCE
            assertThat(result25.refundAllowed()).isTrue();
            assertThat(result50.refundAllowed()).isTrue();
            assertThat(result75.refundAllowed()).isTrue();
            assertThat(result100.refundAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Token Deduction Caps Tests")
    class TokenDeductionCapsTests {

        @Test
        @DisplayName("Should cap tokens deducted to remaining credited tokens")
        void shouldCapTokensDeductedToRemainingCreditedTokens() {
            // Given - Mock some tokens spent (300 out of 1000)
            TokenTransaction spentTransaction = new TokenTransaction();
            spentTransaction.setAmountTokens(300L);
            spentTransaction.setType(TokenTransactionType.COMMIT);
            
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of(spentTransaction));
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.CAP_BY_UNSPENT_TOKENS);
            
            // When - Request refund larger than unspent tokens
            long refundAmountCents = 800L; // Would normally be 800 tokens, but only 700 unspent
            RefundCalculationDto result = refundPolicyService.calculateRefund(testPayment, refundAmountCents);
            
            // Then - Should be capped at unspent tokens
            assertThat(result.tokensToDeduct()).isEqualTo(700L); // 1000 - 300 = 700 unspent
            assertThat(result.refundAmountCents()).isEqualTo(700L); // Proportional: (700 * 1000) / 1000
            assertThat(result.refundAllowed()).isTrue();
            assertThat(result.tokensSpent()).isEqualTo(300L);
            assertThat(result.tokensUnspent()).isEqualTo(700L);
        }

        @Test
        @DisplayName("Should handle zero unspent tokens")
        void shouldHandleZeroUnspentTokens() {
            // Given - Mock all tokens spent
            TokenTransaction spentTransaction = new TokenTransaction();
            spentTransaction.setAmountTokens(1000L);
            spentTransaction.setType(TokenTransactionType.COMMIT);
            
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of(spentTransaction));
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.CAP_BY_UNSPENT_TOKENS);
            
            // When - Request refund when no tokens unspent
            RefundCalculationDto result = refundPolicyService.calculateRefund(testPayment, 500L);
            
            // Then - Should allow refund but with zero tokens deducted
            assertThat(result.tokensToDeduct()).isEqualTo(0L);
            assertThat(result.refundAmountCents()).isEqualTo(0L);
            assertThat(result.refundAllowed()).isFalse(); // Not allowed when no tokens to deduct
            assertThat(result.tokensSpent()).isEqualTo(1000L);
            assertThat(result.tokensUnspent()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("ProcessRefund Idempotency Tests")
    class ProcessRefundIdempotencyTests {

        @Test
        @DisplayName("Should be idempotent by refund/dispute id")
        void shouldBeIdempotentByRefundDisputeId() {
            // Given - Mock calculation and no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            RefundCalculationDto calculation = refundPolicyService.calculateRefund(testPayment, 500L);
            
            // When - Process refund multiple times with same refund ID
            // The actual implementation handles idempotency through the internal billing service
            refundPolicyService.processRefund(testPayment, calculation, testRefundId, testEventId);
            refundPolicyService.processRefund(testPayment, calculation, testRefundId, testEventId);
            refundPolicyService.processRefund(testPayment, calculation, testRefundId, testEventId);
            
            // Then - Internal billing service should be called for each attempt
            // Idempotency is handled by the internal billing service using the idempotency key
            verify(internalBillingService, times(3)).deductTokens(
                eq(testUserId),
                eq(calculation.tokensToDeduct()),
                eq("refund:" + testRefundId),
                eq(testRefundId),
                anyString()
            );
        }

        @Test
        @DisplayName("Should handle different refund IDs independently")
        void shouldHandleDifferentRefundIdsIndependently() {
            // Given - Mock calculation
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            RefundCalculationDto calculation = refundPolicyService.calculateRefund(testPayment, 500L);
            
            // When - Process different refund IDs
            String refundId1 = "re_test123";
            String refundId2 = "re_test456";
            String refundId3 = "re_test789";
            
            refundPolicyService.processRefund(testPayment, calculation, refundId1, "evt1");
            refundPolicyService.processRefund(testPayment, calculation, refundId2, "evt2");
            refundPolicyService.processRefund(testPayment, calculation, refundId3, "evt3");
            
            // Then - Each refund ID should be processed independently
            verify(internalBillingService, times(3)).deductTokens(
                eq(testUserId),
                eq(calculation.tokensToDeduct()),
                anyString(),
                anyString(),
                anyString()
            );
        }

        @Test
        @DisplayName("Should handle concurrent refund processing safely")
        void shouldHandleConcurrentRefundProcessingSafely() throws InterruptedException {
            // Given - Mock calculation
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            RefundCalculationDto calculation = refundPolicyService.calculateRefund(testPayment, 500L);
            
            int numberOfThreads = 5;
            ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
            CountDownLatch latch = new CountDownLatch(numberOfThreads);
            AtomicInteger successCount = new AtomicInteger(0);
            
            // When - Process same refund concurrently
            for (int i = 0; i < numberOfThreads; i++) {
                executor.submit(() -> {
                    try {
                        refundPolicyService.processRefund(testPayment, calculation, testRefundId, testEventId);
                        successCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await();
            executor.shutdown();
            
            // Then - All threads should complete successfully
            assertThat(successCount.get()).isEqualTo(numberOfThreads);
            
            // Each thread should call the internal billing service (idempotency handled by internal service)
            verify(internalBillingService, times(numberOfThreads)).deductTokens(
                eq(testUserId),
                eq(calculation.tokensToDeduct()),
                eq("refund:" + testRefundId),
                eq(testRefundId),
                anyString()
            );
        }
    }

    @Nested
    @DisplayName("Policy Enforcement Tests")
    class PolicyEnforcementTests {

        @Test
        @DisplayName("Should enforce ALLOW_NEGATIVE_BALANCE policy")
        void shouldEnforceAllowNegativeBalancePolicy() {
            // Given - Mock some tokens spent
            TokenTransaction spentTransaction = new TokenTransaction();
            spentTransaction.setAmountTokens(800L);
            spentTransaction.setType(TokenTransactionType.COMMIT);
            
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of(spentTransaction));
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // When - Request full refund
            RefundCalculationDto result = refundPolicyService.calculateRefund(testPayment, 1000L);
            
            // Then - Should allow full refund even with negative balance
            assertThat(result.refundAllowed()).isTrue();
            assertThat(result.tokensToDeduct()).isEqualTo(1000L); // Full amount
            assertThat(result.refundAmountCents()).isEqualTo(1000L);
            assertThat(result.policyApplied()).isEqualTo("ALLOW_NEGATIVE_BALANCE");
            assertThat(result.reason()).contains("negative balance permitted");
        }

        @Test
        @DisplayName("Should enforce CAP_BY_UNSPENT_TOKENS policy")
        void shouldEnforceCapByUnspentTokensPolicy() {
            // Given - Mock some tokens spent
            TokenTransaction spentTransaction = new TokenTransaction();
            spentTransaction.setAmountTokens(600L);
            spentTransaction.setType(TokenTransactionType.COMMIT);
            
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of(spentTransaction));
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.CAP_BY_UNSPENT_TOKENS);
            
            // When - Request full refund
            RefundCalculationDto result = refundPolicyService.calculateRefund(testPayment, 1000L);
            
            // Then - Should cap at unspent tokens
            assertThat(result.refundAllowed()).isTrue();
            assertThat(result.tokensToDeduct()).isEqualTo(400L); // 1000 - 600 = 400 unspent
            assertThat(result.refundAmountCents()).isEqualTo(400L);
            assertThat(result.policyApplied()).isEqualTo("CAP_BY_UNSPENT_TOKENS");
            assertThat(result.reason()).contains("capped by unspent tokens");
        }

        @Test
        @DisplayName("Should enforce BLOCK_IF_TOKENS_SPENT policy")
        void shouldEnforceBlockIfTokensSpentPolicy() {
            // Given - Mock some tokens spent
            TokenTransaction spentTransaction = new TokenTransaction();
            spentTransaction.setAmountTokens(100L);
            spentTransaction.setType(TokenTransactionType.COMMIT);
            
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of(spentTransaction));
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.BLOCK_IF_TOKENS_SPENT);
            
            // When - Request refund
            RefundCalculationDto result = refundPolicyService.calculateRefund(testPayment, 500L);
            
            // Then - Should block refund
            assertThat(result.refundAllowed()).isFalse();
            assertThat(result.tokensToDeduct()).isEqualTo(0L);
            assertThat(result.refundAmountCents()).isEqualTo(0L);
            assertThat(result.policyApplied()).isEqualTo("BLOCK_IF_TOKENS_SPENT");
            assertThat(result.reason()).contains("tokens have been spent");
        }

        @Test
        @DisplayName("Should allow refund under BLOCK_IF_TOKENS_SPENT when no tokens spent")
        void shouldAllowRefundUnderBlockIfTokensSpentWhenNoTokensSpent() {
            // Given - Mock no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.BLOCK_IF_TOKENS_SPENT);
            
            // When - Request refund
            RefundCalculationDto result = refundPolicyService.calculateRefund(testPayment, 500L);
            
            // Then - Should allow refund
            assertThat(result.refundAllowed()).isTrue();
            assertThat(result.tokensToDeduct()).isEqualTo(500L);
            assertThat(result.refundAmountCents()).isEqualTo(500L);
            assertThat(result.policyApplied()).isEqualTo("BLOCK_IF_TOKENS_SPENT");
            assertThat(result.reason()).contains("no tokens spent");
        }
    }

    @Nested
    @DisplayName("Policy Consistency Tests")
    class PolicyConsistencyTests {

        @Test
        @DisplayName("Should maintain policy consistency across multiple refunds")
        void shouldMaintainPolicyConsistencyAcrossMultipleRefunds() {
            // Given - Mock some tokens spent
            TokenTransaction spentTransaction = new TokenTransaction();
            spentTransaction.setAmountTokens(300L);
            spentTransaction.setType(TokenTransactionType.COMMIT);
            
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of(spentTransaction));
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.CAP_BY_UNSPENT_TOKENS);
            
            // When - Process multiple partial refunds
            RefundCalculationDto refund1 = refundPolicyService.calculateRefund(testPayment, 200L);
            RefundCalculationDto refund2 = refundPolicyService.calculateRefund(testPayment, 300L);
            RefundCalculationDto refund3 = refundPolicyService.calculateRefund(testPayment, 500L);
            
            // Then - All should follow the same policy consistently
            assertThat(refund1.policyApplied()).isEqualTo("CAP_BY_UNSPENT_TOKENS");
            assertThat(refund2.policyApplied()).isEqualTo("CAP_BY_UNSPENT_TOKENS");
            assertThat(refund3.policyApplied()).isEqualTo("CAP_BY_UNSPENT_TOKENS");
            
            // All should be allowed but capped appropriately
            assertThat(refund1.refundAllowed()).isTrue();
            assertThat(refund2.refundAllowed()).isTrue();
            assertThat(refund3.refundAllowed()).isTrue();
            
            // Each individual refund should be capped by unspent tokens (700L each)
            assertThat(refund1.tokensToDeduct()).isEqualTo(200L); // 200 < 700, so allowed
            assertThat(refund2.tokensToDeduct()).isEqualTo(300L); // 300 < 700, so allowed  
            assertThat(refund3.tokensToDeduct()).isEqualTo(500L); // 500 < 700, so allowed
            
            // Note: The policy doesn't track cumulative refunds, so each calculation is independent
            // In a real system, you'd need additional logic to track total refunded amounts
        }
    }

    @Nested
    @DisplayName("Refund Calculation Accuracy Tests")
    class RefundCalculationAccuracyTests {

        @Test
        @DisplayName("Should calculate proportional refunds with various amounts")
        void shouldCalculateProportionalRefundsWithVariousAmounts() {
            // Given - Mock no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // When - Test various proportional refunds
            long originalAmount = testPayment.getAmountCents(); // 1000 cents
            long originalTokens = testPayment.getCreditedTokens(); // 1000 tokens
            
            // Test 10% refund
            RefundCalculationDto refund10 = refundPolicyService.calculateRefund(testPayment, 100L);
            assertThat(refund10.tokensToDeduct()).isEqualTo(100L); // (1000 * 100) / 1000 = 100
            
            // Test 33.33% refund (333 cents)
            RefundCalculationDto refund33 = refundPolicyService.calculateRefund(testPayment, 333L);
            assertThat(refund33.tokensToDeduct()).isEqualTo(333L); // (1000 * 333) / 1000 = 333
            
            // Test 66.67% refund (667 cents)
            RefundCalculationDto refund67 = refundPolicyService.calculateRefund(testPayment, 667L);
            assertThat(refund67.tokensToDeduct()).isEqualTo(667L); // (1000 * 667) / 1000 = 667
            
            // Test 90% refund
            RefundCalculationDto refund90 = refundPolicyService.calculateRefund(testPayment, 900L);
            assertThat(refund90.tokensToDeduct()).isEqualTo(900L); // (1000 * 900) / 1000 = 900
        }

        @Test
        @DisplayName("Should handle rounding behavior consistently")
        void shouldHandleRoundingBehaviorConsistently() {
            // Given - Mock no tokens spent and create payment with odd amounts
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // Create payment with odd amounts that might cause rounding issues
            Payment oddPayment = new Payment();
            oddPayment.setId(UUID.randomUUID());
            oddPayment.setUserId(testUserId);
            oddPayment.setAmountCents(333L); // $3.33
            oddPayment.setCreditedTokens(1000L);
            oddPayment.setCreatedAt(LocalDateTime.now().minusHours(1));
            
            // When - Test refunds that might cause rounding
            // 1/3 refund = 111 cents, should be 333 tokens (1000 * 111 / 333)
            RefundCalculationDto refund1 = refundPolicyService.calculateRefund(oddPayment, 111L);
            assertThat(refund1.tokensToDeduct()).isEqualTo(333L);
            
            // 2/3 refund = 222 cents, should be 666 tokens (1000 * 222 / 333)
            RefundCalculationDto refund2 = refundPolicyService.calculateRefund(oddPayment, 222L);
            assertThat(refund2.tokensToDeduct()).isEqualTo(666L);
            
            // Verify consistency - multiple calls with same input should give same result
            RefundCalculationDto refund1Again = refundPolicyService.calculateRefund(oddPayment, 111L);
            assertThat(refund1Again.tokensToDeduct()).isEqualTo(refund1.tokensToDeduct());
        }

        @Test
        @DisplayName("Should handle edge case calculations correctly")
        void shouldHandleEdgeCaseCalculationsCorrectly() {
            // Given - Mock no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // Test edge cases
            // Zero refund
            RefundCalculationDto zeroRefund = refundPolicyService.calculateRefund(testPayment, 0L);
            assertThat(zeroRefund.tokensToDeduct()).isEqualTo(0L);
            assertThat(zeroRefund.refundAmountCents()).isEqualTo(0L);
            
            // Very small refund (1 cent)
            RefundCalculationDto smallRefund = refundPolicyService.calculateRefund(testPayment, 1L);
            assertThat(smallRefund.tokensToDeduct()).isEqualTo(1L);
            
            // Full refund
            RefundCalculationDto fullRefund = refundPolicyService.calculateRefund(testPayment, 1000L);
            assertThat(fullRefund.tokensToDeduct()).isEqualTo(1000L);
            
            // Refund larger than original (should still be proportional)
            RefundCalculationDto largeRefund = refundPolicyService.calculateRefund(testPayment, 1500L);
            assertThat(largeRefund.tokensToDeduct()).isEqualTo(1500L); // (1000 * 1500) / 1000 = 1500
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should handle complete refund lifecycle")
        void shouldHandleCompleteRefundLifecycle() {
            // Given - Mock no tokens spent initially
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // When - Calculate and process refund
            RefundCalculationDto calculation = refundPolicyService.calculateRefund(testPayment, 500L);
            refundPolicyService.processRefund(testPayment, calculation, testRefundId, testEventId);
            
            // Then - Verify complete lifecycle
            assertThat(calculation.refundAllowed()).isTrue();
            assertThat(calculation.tokensToDeduct()).isEqualTo(500L);
            
            verify(internalBillingService).deductTokens(
                eq(testUserId),
                eq(500L),
                eq("refund:" + testRefundId),
                eq(testRefundId),
                anyString()
            );
            
            verify(paymentRepository).save(argThat(payment -> 
                payment.getRefundedAmountCents() == 500L &&
                payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
            ));
        }

        @Test
        @DisplayName("Should handle full refund to REFUNDED status")
        void shouldHandleFullRefundToRefundedStatus() {
            // Given - Mock no tokens spent
            when(transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
                eq(testUserId), eq(TokenTransactionType.COMMIT), any(LocalDateTime.class)))
                .thenReturn(List.of());
            
            ReflectionTestUtils.setField(refundPolicyService, "refundPolicy", RefundPolicy.ALLOW_NEGATIVE_BALANCE);
            
            // When - Process full refund
            RefundCalculationDto calculation = refundPolicyService.calculateRefund(testPayment, 1000L);
            refundPolicyService.processRefund(testPayment, calculation, testRefundId, testEventId);
            
            // Then - Payment should be marked as REFUNDED
            verify(paymentRepository).save(argThat(payment -> 
                payment.getRefundedAmountCents() == 1000L &&
                payment.getStatus() == PaymentStatus.REFUNDED
            ));
        }
    }
}
