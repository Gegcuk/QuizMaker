package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionService;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SubscriptionService covering idempotency, state transitions, and payment handling.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private BillingMetricsService metricsService;
    
    @Mock
    private SubscriptionStatusRepository subscriptionStatusRepository;
    
    private SubscriptionService subscriptionService;
    
    private UUID testUserId;
    private String testSubscriptionId;
    private String testPriceId;
    private String testEventId;
    private long testPeriodStart;
    private long testTokensPerPeriod;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionServiceImpl(
            internalBillingService, 
            metricsService, 
            subscriptionStatusRepository
        );
        
        testUserId = UUID.randomUUID();
        testSubscriptionId = "sub_test123";
        testPriceId = "price_test456";
        testEventId = "evt_test789";
        testPeriodStart = System.currentTimeMillis() / 1000;
        testTokensPerPeriod = 10000L;
    }

    @Nested
    @DisplayName("handleSubscriptionPaymentSuccess Tests")
    class HandleSubscriptionPaymentSuccessTests {

        @Test
        @DisplayName("Should be idempotent per (userId, subscriptionId, periodStart, eventId)")
        void shouldBeIdempotentPerUserIdSubscriptionIdPeriodStartEventId() {
            // Given - Mock the internal billing service to handle idempotency
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            // Mock the internal billing service to simulate idempotency behavior
            // First call succeeds, subsequent calls are handled by the internal billing service
            doNothing().doThrow(new RuntimeException("Already processed"))
                .when(internalBillingService).creditPurchase(
                    eq(testUserId), 
                    eq(testTokensPerPeriod), 
                    anyString(), 
                    eq(testSubscriptionId), 
                    anyString()
                );
            
            // When - Call the method multiple times with same parameters
            boolean firstResult = subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
            
            boolean secondResult = subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
            
            boolean thirdResult = subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
            
            // Then - Verify behavior
            assertThat(firstResult).isTrue();
            assertThat(secondResult).isFalse(); // Should return false due to exception
            assertThat(thirdResult).isFalse(); // Should return false due to exception
            
            // Verify internal billing service was called for each attempt
            verify(internalBillingService, times(3)).creditPurchase(
                eq(testUserId), 
                eq(testTokensPerPeriod), 
                anyString(), 
                eq(testSubscriptionId), 
                anyString()
            );
            
            // Verify metrics were incremented only once (first successful call)
            verify(metricsService, times(1)).incrementTokensCredited(
                eq(testUserId), 
                eq(testTokensPerPeriod), 
                eq("SUBSCRIPTION")
            );
        }

        @Test
        @DisplayName("Should handle concurrent calls safely")
        void shouldHandleConcurrentCallsSafely() throws InterruptedException {
            // Given
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            int numberOfThreads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
            CountDownLatch latch = new CountDownLatch(numberOfThreads);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            
            // When - Make concurrent calls
            for (int i = 0; i < numberOfThreads; i++) {
                executor.submit(() -> {
                    try {
                        boolean result = subscriptionService.handleSubscriptionPaymentSuccess(
                            testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
                        
                        if (result) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await();
            executor.shutdown();
            
            // Then - All calls should complete (some may succeed, some may fail due to race conditions)
            assertThat(successCount.get() + failureCount.get()).isEqualTo(numberOfThreads);
            assertThat(successCount.get()).isGreaterThan(0); // At least one should succeed
            
            // Verify internal billing service was called for each attempt
            verify(internalBillingService, times(numberOfThreads)).creditPurchase(
                eq(testUserId), 
                eq(testTokensPerPeriod), 
                anyString(), 
                eq(testSubscriptionId), 
                anyString()
            );
        }

        @Test
        @DisplayName("Should throw exception for zero or negative tokens per period")
        void shouldThrowExceptionForZeroOrNegativeTokensPerPeriod() {
            // When & Then
            assertThatThrownBy(() -> subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, 0L, testEventId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokensPerPeriod must be > 0");
            
            assertThatThrownBy(() -> subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, -100L, testEventId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokensPerPeriod must be > 0");
        }

        @Test
        @DisplayName("Should handle exceptions gracefully and return false")
        void shouldHandleExceptionsGracefullyAndReturnFalse() {
            // Given - Mock exception
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenThrow(new RuntimeException("Database error"));
            
            // When
            boolean result = subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
            
            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getTokensPerPeriod Tests")
    class GetTokensPerPeriodTests {

        @Test
        @DisplayName("Should return stable value across invocations")
        void shouldReturnStableValueAcrossInvocations() {
            // When - Call multiple times
            long firstResult = subscriptionService.getTokensPerPeriod(testSubscriptionId, testPriceId);
            long secondResult = subscriptionService.getTokensPerPeriod(testSubscriptionId, testPriceId);
            long thirdResult = subscriptionService.getTokensPerPeriod(testSubscriptionId, testPriceId);
            
            // Then - All results should be the same
            assertThat(firstResult).isEqualTo(10000L); // DEFAULT_TOKENS_PER_PERIOD
            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(thirdResult).isEqualTo(firstResult);
        }

        @Test
        @DisplayName("Should return consistent value for different subscription IDs")
        void shouldReturnConsistentValueForDifferentSubscriptionIds() {
            // When
            long result1 = subscriptionService.getTokensPerPeriod("sub_1", testPriceId);
            long result2 = subscriptionService.getTokensPerPeriod("sub_2", testPriceId);
            long result3 = subscriptionService.getTokensPerPeriod("sub_3", testPriceId);
            
            // Then - All should return the same default value
            assertThat(result1).isEqualTo(10000L);
            assertThat(result2).isEqualTo(10000L);
            assertThat(result3).isEqualTo(10000L);
        }

        @Test
        @DisplayName("Should return consistent value for different price IDs")
        void shouldReturnConsistentValueForDifferentPriceIds() {
            // When
            long result1 = subscriptionService.getTokensPerPeriod(testSubscriptionId, "price_1");
            long result2 = subscriptionService.getTokensPerPeriod(testSubscriptionId, "price_2");
            long result3 = subscriptionService.getTokensPerPeriod(testSubscriptionId, "price_3");
            
            // Then - All should return the same default value
            assertThat(result1).isEqualTo(10000L);
            assertThat(result2).isEqualTo(10000L);
            assertThat(result3).isEqualTo(10000L);
        }
    }

    @Nested
    @DisplayName("Subscription State Transitions Tests")
    class SubscriptionStateTransitionsTests {

        @Test
        @DisplayName("Should handle Active → Blocked → Active transitions")
        void shouldHandleActiveToBlockedToActiveTransitions() {
            // Given - Start with active subscription (no status record)
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            // When - Verify initial state is active
            boolean initialActive = subscriptionService.isSubscriptionActive(testUserId);
            
            // Block the subscription
            subscriptionService.blockSubscription(testUserId, "Test blocking");
            
            // Verify blocked state
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.of(createBlockedStatus()));
            boolean blockedActive = subscriptionService.isSubscriptionActive(testUserId);
            
            // Unblock the subscription
            subscriptionService.unblockSubscription(testUserId);
            
            // Verify active state again
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.of(createActiveStatus()));
            boolean finalActive = subscriptionService.isSubscriptionActive(testUserId);
            
            // Then
            assertThat(initialActive).isTrue(); // Default to active
            assertThat(blockedActive).isFalse(); // Should be blocked
            assertThat(finalActive).isTrue(); // Should be active again
            
            // Verify repository interactions (accounting for all calls including isSubscriptionActive)
            verify(subscriptionStatusRepository, atLeast(3)).findByUserId(testUserId);
            verify(subscriptionStatusRepository, atLeast(2)).save(any(SubscriptionStatus.class));
        }

        @Test
        @DisplayName("Should handle canceled state properly")
        void shouldHandleCanceledStateProperly() {
            // Given - Simulate subscription deletion/cancellation
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            // When - Handle subscription deletion
            subscriptionService.handleSubscriptionDeleted(testUserId, testSubscriptionId, "User canceled");
            
            // Then - Verify subscription is blocked (canceled)
            verify(subscriptionStatusRepository).findByUserId(testUserId);
            verify(subscriptionStatusRepository).save(argThat(status -> 
                status.getUserId().equals(testUserId) && 
                status.isBlocked() && 
                status.getBlockReason().equals("subscription_deleted: User canceled")
            ));
        }

        @Test
        @DisplayName("Should handle payment failure recovery")
        void shouldHandlePaymentFailureRecovery() {
            // Given - Initial active subscription
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            // When - Payment fails
            subscriptionService.handleSubscriptionPaymentFailure(testUserId, testSubscriptionId, "Insufficient funds");
            
            // Verify subscription is blocked
            verify(subscriptionStatusRepository).findByUserId(testUserId);
            verify(subscriptionStatusRepository).save(argThat(status -> 
                status.getUserId().equals(testUserId) && 
                status.isBlocked() && 
                status.getBlockReason().equals("payment_failed: Insufficient funds")
            ));
            
            // When - Payment succeeds later (recovery)
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.of(createBlockedStatus()));
            
            subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
            
            // Then - Subscription should be unblocked and tokens credited
            verify(subscriptionStatusRepository, times(2)).findByUserId(testUserId);
            verify(internalBillingService).creditPurchase(
                eq(testUserId), 
                eq(testTokensPerPeriod), 
                anyString(), 
                eq(testSubscriptionId), 
                anyString()
            );
        }

        @Test
        @DisplayName("Should handle multiple payment failures gracefully")
        void shouldHandleMultiplePaymentFailuresGracefully() {
            // Given
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            // When - Multiple payment failures
            subscriptionService.handleSubscriptionPaymentFailure(testUserId, testSubscriptionId, "First failure");
            subscriptionService.handleSubscriptionPaymentFailure(testUserId, testSubscriptionId, "Second failure");
            subscriptionService.handleSubscriptionPaymentFailure(testUserId, testSubscriptionId, "Third failure");
            
            // Then - Should handle all gracefully
            verify(subscriptionStatusRepository, times(3)).findByUserId(testUserId);
            verify(subscriptionStatusRepository, times(3)).save(any(SubscriptionStatus.class));
        }

        @Test
        @DisplayName("Should handle concurrent state transitions safely")
        void shouldHandleConcurrentStateTransitionsSafely() throws InterruptedException {
            // Given
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            int numberOfThreads = 5;
            ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
            CountDownLatch latch = new CountDownLatch(numberOfThreads);
            
            // When - Concurrent state transitions
            for (int i = 0; i < numberOfThreads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        if (threadId % 2 == 0) {
                            subscriptionService.blockSubscription(testUserId, "Concurrent block " + threadId);
                        } else {
                            subscriptionService.unblockSubscription(testUserId);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await();
            executor.shutdown();
            
            // Then - Should handle concurrent operations safely
            verify(subscriptionStatusRepository, atLeast(numberOfThreads)).findByUserId(testUserId);
            // Note: unblockSubscription only saves if a status exists, so save count may be less
            verify(subscriptionStatusRepository, atLeast(3)).save(any(SubscriptionStatus.class));
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should handle complete subscription lifecycle")
        void shouldHandleCompleteSubscriptionLifecycle() {
            // Given - Fresh user
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.empty());
            
            // When - Complete lifecycle
            // 1. Initial payment success
            boolean firstPayment = subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart, testTokensPerPeriod, testEventId);
            
            // 2. Payment failure
            subscriptionService.handleSubscriptionPaymentFailure(testUserId, testSubscriptionId, "Payment failed");
            
            // 3. Recovery payment success
            when(subscriptionStatusRepository.findByUserId(testUserId))
                .thenReturn(Optional.of(createBlockedStatus()));
            
            boolean recoveryPayment = subscriptionService.handleSubscriptionPaymentSuccess(
                testUserId, testSubscriptionId, testPeriodStart + 86400, testTokensPerPeriod, testEventId + "_recovery");
            
            // 4. Subscription cancellation
            subscriptionService.handleSubscriptionDeleted(testUserId, testSubscriptionId, "User requested cancellation");
            
            // Then
            assertThat(firstPayment).isTrue();
            assertThat(recoveryPayment).isTrue();
            
            // Verify all operations were handled
            verify(internalBillingService, times(2)).creditPurchase(
                eq(testUserId), 
                eq(testTokensPerPeriod), 
                anyString(), 
                eq(testSubscriptionId), 
                anyString()
            );
            
            verify(metricsService, times(2)).incrementTokensCredited(
                eq(testUserId), 
                eq(testTokensPerPeriod), 
                eq("SUBSCRIPTION")
            );
            
            verify(subscriptionStatusRepository, atLeast(4)).findByUserId(testUserId);
            verify(subscriptionStatusRepository, atLeast(4)).save(any(SubscriptionStatus.class));
        }
    }

    // Helper methods
    private SubscriptionStatus createActiveStatus() {
        SubscriptionStatus status = new SubscriptionStatus();
        status.setUserId(testUserId);
        status.setSubscriptionId(testSubscriptionId);
        status.setBlocked(false);
        status.setBlockReason(null);
        return status;
    }

    private SubscriptionStatus createBlockedStatus() {
        SubscriptionStatus status = new SubscriptionStatus();
        status.setUserId(testUserId);
        status.setSubscriptionId(testSubscriptionId);
        status.setBlocked(true);
        status.setBlockReason("Test blocking");
        return status;
    }
}
