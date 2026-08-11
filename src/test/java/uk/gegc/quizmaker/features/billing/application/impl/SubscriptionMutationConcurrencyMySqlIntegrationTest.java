package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationClaim;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationCoordinator;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationService;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.exception.SubscriptionMutationConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationState;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationType;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionMutationOperationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Import({
        SubscriptionMutationCoordinatorImpl.class,
        SubscriptionMutationServiceImpl.class,
        SubscriptionMutationConcurrencyMySqlIntegrationTest.ClockTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Subscription mutation concurrency with MySQL")
class SubscriptionMutationConcurrencyMySqlIntegrationTest {

    private static final String SUBSCRIPTION_ID = "sub_concurrency_owner";
    private static final String CUSTOMER_ID = "cus_concurrency_owner";
    private static final String ORIGINAL_PRICE = "price_basic";

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfiguration {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private SubscriptionMutationService mutationService;

    @Autowired
    private SubscriptionMutationCoordinator mutationCoordinator;

    @Autowired
    private SubscriptionMutationOperationRepository operationRepository;

    @Autowired
    private SubscriptionStatusRepository statusRepository;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private BillingMetricsService billingMetricsService;

    private UUID userId;
    private AtomicReference<RemoteState> remoteState;
    private AtomicInteger updateCalls;
    private AtomicInteger cancelCalls;
    private AtomicInteger ownershipLookups;
    private AtomicBoolean pauseNextMutation;
    private AtomicBoolean pauseSecondOwnershipLookup;
    private AtomicReference<CountDownLatch> mutationEntered;
    private AtomicReference<CountDownLatch> releaseMutation;
    private AtomicReference<CountDownLatch> secondOwnershipLookupEntered;
    private AtomicReference<CountDownLatch> releaseSecondOwnershipLookup;

    @BeforeEach
    void setUp() throws StripeException {
        clearDatabase();
        userId = UUID.randomUUID();
        remoteState = new AtomicReference<>(new RemoteState("active", ORIGINAL_PRICE));
        updateCalls = new AtomicInteger();
        cancelCalls = new AtomicInteger();
        ownershipLookups = new AtomicInteger();
        pauseNextMutation = new AtomicBoolean();
        pauseSecondOwnershipLookup = new AtomicBoolean();
        mutationEntered = new AtomicReference<>(new CountDownLatch(0));
        releaseMutation = new AtomicReference<>(new CountDownLatch(0));
        secondOwnershipLookupEntered = new AtomicReference<>(new CountDownLatch(0));
        releaseSecondOwnershipLookup = new AtomicReference<>(new CountDownLatch(0));

        SubscriptionStatus status = new SubscriptionStatus();
        status.setUserId(userId);
        status.setSubscriptionId(SUBSCRIPTION_ID);
        statusRepository.saveAndFlush(status);

        when(stripeService.retrieveSubscription(SUBSCRIPTION_ID))
                .thenAnswer(invocation -> subscription(remoteState.get()));
        when(stripeService.retrieveCustomerRaw(CUSTOMER_ID)).thenAnswer(invocation -> {
            awaitReleaseIfSecondOwnershipLookupPaused(ownershipLookups.incrementAndGet());
            return ownerCustomer();
        });
        when(stripeService.updateSubscription(any(Subscription.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    updateCalls.incrementAndGet();
                    awaitReleaseIfPaused();
                    String targetPrice = invocation.getArgument(1);
                    remoteState.set(new RemoteState("active", targetPrice));
                    return subscription(remoteState.get());
                });
        when(stripeService.cancelSubscription(any(Subscription.class), anyString()))
                .thenAnswer(invocation -> {
                    cancelCalls.incrementAndGet();
                    awaitReleaseIfPaused();
                    remoteState.updateAndGet(current -> new RemoteState("canceled", current.priceId()));
                    return subscription(remoteState.get());
                });
    }

    @AfterEach
    void tearDown() {
        releaseMutation.get().countDown();
        releaseSecondOwnershipLookup.get().countDown();
        clearDatabase();
    }

    @Test
    @DisplayName("Concurrent legacy cancellations produce one Stripe cancellation and two successful responses")
    void concurrentLegacyCancelsProduceOneEconomicMutation() throws Exception {
        pauseNextMutation();
        pauseSecondOwnershipLookup();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Subscription> first = executor.submit(
                    () -> mutationService.cancelSubscription(userId, SUBSCRIPTION_ID));
            assertThat(mutationEntered.get().await(2, TimeUnit.SECONDS)).isTrue();

            Future<Subscription> second = executor.submit(
                    () -> mutationService.cancelSubscription(userId, SUBSCRIPTION_ID));
            assertThat(secondOwnershipLookupEntered.get().await(2, TimeUnit.SECONDS)).isTrue();
            releaseMutation.get().countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("canceled");
            releaseSecondOwnershipLookup.get().countDown();
            assertThat(second.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("canceled");
        } finally {
            releaseMutation.get().countDown();
            releaseSecondOwnershipLookup.get().countDown();
            executor.shutdownNow();
        }

        assertThat(cancelCalls).hasValue(1);
        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(statusRepository.findByUserId(userId)).get().satisfies(status -> {
            assertThat(status.isBlocked()).isTrue();
            assertThat(status.getBlockReason()).isEqualTo("subscription_cancelled_by_user");
        });
    }

    @Test
    @DisplayName("Concurrent exact updates share one Stripe operation and converge on the requested price")
    void concurrentExactUpdatesProduceOneEconomicMutation() throws Exception {
        pauseNextMutation();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Subscription> first = executor.submit(() -> mutationService.updateSubscription(
                    userId, SUBSCRIPTION_ID, "price_pro", "same-update-key"));
            assertThat(mutationEntered.get().await(2, TimeUnit.SECONDS)).isTrue();

            Future<Subscription> second = executor.submit(() -> mutationService.updateSubscription(
                    userId, SUBSCRIPTION_ID, "price_pro", "same-update-key"));
            verify(stripeService, timeout(2_000).atLeast(2)).retrieveSubscription(SUBSCRIPTION_ID);
            releaseMutation.get().countDown();

            assertThat(priceId(first.get(5, TimeUnit.SECONDS))).isEqualTo("price_pro");
            assertThat(priceId(second.get(5, TimeUnit.SECONDS))).isEqualTo("price_pro");
        } finally {
            executor.shutdownNow();
        }

        assertThat(updateCalls).hasValue(1);
        assertThat(operationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cancellation waits for an active update and deterministically becomes the terminal state")
    void updateRacingCancellationConvergesOnCancelled() throws Exception {
        pauseNextMutation();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Subscription> update = executor.submit(() -> mutationService.updateSubscription(
                    userId, SUBSCRIPTION_ID, "price_pro", "update-before-cancel"));
            assertThat(mutationEntered.get().await(2, TimeUnit.SECONDS)).isTrue();

            Future<Subscription> cancel = executor.submit(() -> mutationService.cancelSubscription(
                    userId, SUBSCRIPTION_ID, "terminal-cancel"));
            verify(stripeService, timeout(2_000).atLeast(2)).retrieveSubscription(SUBSCRIPTION_ID);
            releaseMutation.get().countDown();

            assertThat(priceId(update.get(5, TimeUnit.SECONDS))).isEqualTo("price_pro");
            assertThat(cancel.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("canceled");
        } finally {
            executor.shutdownNow();
        }

        assertThat(updateCalls).hasValue(1);
        assertThat(cancelCalls).hasValue(1);
        assertThat(remoteState.get().status()).isEqualTo("canceled");
    }

    @Test
    @DisplayName("An update racing an active cancellation is rejected after cancellation wins")
    void cancellationRacingUpdateRejectsUpdateAfterTerminalState() throws Exception {
        pauseNextMutation();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Subscription> cancel = executor.submit(() -> mutationService.cancelSubscription(
                    userId, SUBSCRIPTION_ID, "cancel-first"));
            assertThat(mutationEntered.get().await(2, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> updateFailure = executor.submit(() -> captureFailure(() ->
                    mutationService.updateSubscription(
                            userId, SUBSCRIPTION_ID, "price_pro", "update-after-cancel")));
            verify(stripeService, timeout(2_000).atLeast(2)).retrieveSubscription(SUBSCRIPTION_ID);
            releaseMutation.get().countDown();

            assertThat(cancel.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("canceled");
            assertThat(updateFailure.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(SubscriptionMutationConflictException.class);
        } finally {
            executor.shutdownNow();
        }

        assertThat(cancelCalls).hasValue(1);
        assertThat(updateCalls).hasValue(0);
        assertThat(remoteState.get().status()).isEqualTo("canceled");
    }

    @Test
    @DisplayName("Reusing one key for a different target conflicts before a second Stripe update")
    void changedTargetWithSameKeyConflictsBeforeStripeMutation() throws Exception {
        mutationService.updateSubscription(userId, SUBSCRIPTION_ID, "price_pro", "stable-update-key");

        assertThatThrownBy(() -> mutationService.updateSubscription(
                userId, SUBSCRIPTION_ID, "price_enterprise", "stable-update-key"))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(updateCalls).hasValue(1);
        assertThat(remoteState.get().priceId()).isEqualTo("price_pro");
    }

    @Test
    @DisplayName("A remote success left in progress is reconciled without repeating the Stripe mutation")
    void remoteSuccessWithoutLocalCompletionIsRecoveredByExactRetry() throws Exception {
        SubscriptionMutationClaim abandoned = mutationCoordinator.claim(
                userId,
                SUBSCRIPTION_ID,
                SubscriptionMutationType.CANCEL,
                null,
                "lost-response-key",
                false
        );
        assertThat(abandoned.action()).isEqualTo(SubscriptionMutationClaim.Action.EXECUTE);
        remoteState.set(new RemoteState("canceled", ORIGINAL_PRICE));

        Subscription recovered = mutationService.cancelSubscription(
                userId, SUBSCRIPTION_ID, "lost-response-key");

        assertThat(recovered.getStatus()).isEqualTo("canceled");
        assertThat(cancelCalls).hasValue(0);
        assertThat(mutationCoordinator.getState(abandoned.operationId(), userId))
                .contains(SubscriptionMutationState.SUCCEEDED);
        assertThat(statusRepository.findByUserId(userId)).get().satisfies(status -> {
            assertThat(status.isBlocked()).isTrue();
            assertThat(status.getBlockReason()).isEqualTo("subscription_cancelled_by_user");
        });
    }

    private void pauseNextMutation() {
        mutationEntered.set(new CountDownLatch(1));
        releaseMutation.set(new CountDownLatch(1));
        pauseNextMutation.set(true);
    }

    private void pauseSecondOwnershipLookup() {
        secondOwnershipLookupEntered.set(new CountDownLatch(1));
        releaseSecondOwnershipLookup.set(new CountDownLatch(1));
        pauseSecondOwnershipLookup.set(true);
    }

    private void awaitReleaseIfPaused() throws InterruptedException {
        if (pauseNextMutation.compareAndSet(true, false)) {
            mutationEntered.get().countDown();
            if (!releaseMutation.get().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test mutation barrier timed out");
            }
        }
    }

    private void awaitReleaseIfSecondOwnershipLookupPaused(int lookupNumber) throws InterruptedException {
        if (lookupNumber == 2 && pauseSecondOwnershipLookup.compareAndSet(true, false)) {
            secondOwnershipLookupEntered.get().countDown();
            if (!releaseSecondOwnershipLookup.get().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test ownership barrier timed out");
            }
        }
    }

    private Throwable captureFailure(ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Subscription subscription(RemoteState state) {
        Subscription subscription = mock(Subscription.class);
        SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
        SubscriptionItem item = mock(SubscriptionItem.class);
        Price price = mock(Price.class);
        when(subscription.getId()).thenReturn(SUBSCRIPTION_ID);
        when(subscription.getCustomer()).thenReturn(CUSTOMER_ID);
        when(subscription.getStatus()).thenReturn(state.status());
        when(subscription.getItems()).thenReturn(items);
        when(items.getData()).thenReturn(List.of(item));
        when(item.getPrice()).thenReturn(price);
        when(price.getId()).thenReturn(state.priceId());
        return subscription;
    }

    private Customer ownerCustomer() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setMetadata(Map.of("userId", userId.toString()));
        return customer;
    }

    private String priceId(Subscription subscription) {
        return subscription.getItems().getData().get(0).getPrice().getId();
    }

    private void clearDatabase() {
        operationRepository.deleteAllInBatch();
        statusRepository.deleteAllInBatch();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record RemoteState(String status, String priceId) {
    }
}
