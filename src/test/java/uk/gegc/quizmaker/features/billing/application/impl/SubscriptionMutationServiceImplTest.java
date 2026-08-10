package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationClaim;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationCoordinator;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeSubscriptionUnavailableException;
import uk.gegc.quizmaker.features.billing.domain.exception.SubscriptionMutationConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationType;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("Subscription mutation ownership service")
class SubscriptionMutationServiceImplTest {

    private final UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private final String subscriptionId = "sub_owner";
    private final String customerId = "cus_owner";

    @Mock
    private SubscriptionStatusRepository subscriptionStatusRepository;

    @Mock
    private StripeService stripeService;

    @Mock
    private BillingMetricsService billingMetricsService;

    @Mock
    private SubscriptionMutationCoordinator mutationCoordinator;

    private SubscriptionMutationService subscriptionMutationService;

    @BeforeEach
    void setUp() {
        subscriptionMutationService = new SubscriptionMutationServiceImpl(
                subscriptionStatusRepository,
                stripeService,
                billingMetricsService,
                mutationCoordinator
        );
    }

    @Nested
    @DisplayName("Update subscription")
    class UpdateSubscriptionTests {

        @Test
        @DisplayName("updates an owned, locally mapped subscription after Stripe customer ownership validation")
        void updateSubscription_withOwnedSubscription_updatesExactlyOnce() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            Subscription updatedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            stubOwnedSubscription(localStatus, verifiedSubscription, customer(userId, customerId));
            stubExecuteClaim(SubscriptionMutationType.UPDATE, "price_pro");
            when(stripeService.updateSubscription(verifiedSubscription, "price_pro", "stripe-operation-key"))
                    .thenReturn(updatedSubscription);

            Subscription result = subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro");

            assertThat(result).isSameAs(updatedSubscription);
            verify(stripeService).updateSubscription(verifiedSubscription, "price_pro", "stripe-operation-key");
            verify(mutationCoordinator).complete(operationId(), userId);
            verify(billingMetricsService).recordSubscriptionMutation("update", "allowed", "updated");
        }

        @Test
        @DisplayName("rejects a foreign subscription ID before any Stripe lookup or mutation")
        void updateSubscription_withForeignClientId_rejectsBeforeStripeInteraction() {
            when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(localStatus(subscriptionId)));

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, "sub_other_user", "price_pro"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Subscription mutation is not permitted");

            verifyNoInteractions(stripeService);
            verify(billingMetricsService).recordSubscriptionMutation("update", "denied", "local_mapping_mismatch");
        }

        @Test
        @DisplayName("rejects a locally mapped subscription when the Stripe customer belongs to another user")
        void updateSubscription_withForeignStripeCustomer_rejectsWithoutMutation() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            stubOwnedSubscription(localStatus, verifiedSubscription, customer(UUID.randomUUID(), customerId));

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Subscription mutation is not permitted");

            verify(stripeService, never()).updateSubscription(any(Subscription.class), any(), any());
            verify(billingMetricsService).recordSubscriptionMutation("update", "denied", "stripe_customer_mismatch");
        }

        @Test
        @DisplayName("rejects a subscription whose Stripe customer has no immutable user mapping")
        void updateSubscription_withCustomerMissingUserMetadata_rejectsWithoutMutation() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            Customer customerWithoutOwner = new Customer();
            customerWithoutOwner.setId(customerId);
            customerWithoutOwner.setMetadata(Map.of());
            stubOwnedSubscription(localStatus, verifiedSubscription, customerWithoutOwner);

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Subscription mutation is not permitted");

            verify(stripeService, never()).updateSubscription(any(Subscription.class), any(), any());
            verify(billingMetricsService).recordSubscriptionMutation("update", "denied", "stripe_customer_mismatch");
        }

        @Test
        @DisplayName("rejects a subscription when its customer identifier does not equal the retrieved customer")
        void updateSubscription_withMismatchedStripeCustomerIdentifier_rejectsWithoutMutation() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            stubOwnedSubscription(localStatus, verifiedSubscription, customer(userId, "cus_different"));

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Subscription mutation is not permitted");

            verify(stripeService, never()).updateSubscription(any(Subscription.class), any(), any());
            verify(billingMetricsService).recordSubscriptionMutation("update", "denied", "stripe_customer_mismatch");
        }

        @Test
        @DisplayName("rejects an ambiguous local subscription mapping without mutating Stripe")
        void updateSubscription_withAmbiguousLocalMapping_rejectsWithoutStripeInteraction() {
            SubscriptionStatus expected = localStatus(subscriptionId);
            SubscriptionStatus duplicate = localStatus(subscriptionId);
            duplicate.setId(UUID.randomUUID());
            duplicate.setUserId(UUID.randomUUID());
            when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(expected));
            when(subscriptionStatusRepository.findAllBySubscriptionId(subscriptionId)).thenReturn(List.of(expected, duplicate));

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(ForbiddenException.class);

            verifyNoInteractions(stripeService);
            verify(billingMetricsService).recordSubscriptionMutation("update", "denied", "ambiguous_local_mapping");
        }

        @Test
        @DisplayName("rejects a stale Stripe subscription without revealing its existence or mutating Stripe")
        void updateSubscription_withStaleStripeSubscription_rejectsWithoutMutation() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(localStatus));
            when(subscriptionStatusRepository.findAllBySubscriptionId(subscriptionId)).thenReturn(List.of(localStatus));
            when(stripeService.retrieveSubscription(subscriptionId)).thenThrow(notFound());

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Subscription mutation is not permitted");

            verify(stripeService, never()).updateSubscription(any(Subscription.class), any(), any());
            verify(billingMetricsService).recordSubscriptionMutation("update", "denied", "stale_subscription");
        }

        @Test
        @DisplayName("returns a typed retryable failure when Stripe is unavailable before the update")
        void updateSubscription_whenStripeIsUnavailable_returnsRetryableFailure() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            StripeException unavailable = org.mockito.Mockito.mock(StripeException.class);
            when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(localStatus));
            when(subscriptionStatusRepository.findAllBySubscriptionId(subscriptionId)).thenReturn(List.of(localStatus));
            when(stripeService.retrieveSubscription(subscriptionId)).thenThrow(unavailable);

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(StripeSubscriptionUnavailableException.class)
                    .hasCause(unavailable);

            verify(stripeService, never()).updateSubscription(any(Subscription.class), any(), any());
            verify(billingMetricsService).recordSubscriptionMutation("update", "failed", "stripe_unavailable");
        }

        @Test
        @DisplayName("rejects an update to an already cancelled subscription without mutating Stripe")
        void updateSubscription_withCancelledSubscription_returnsConflictWithoutMutation() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription cancelled = stripeSubscription(subscriptionId, customerId, "canceled");
            stubOwnedSubscription(localStatus, cancelled, customer(userId, customerId));

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(userId, subscriptionId, "price_pro"))
                    .isInstanceOf(SubscriptionMutationConflictException.class);

            verify(stripeService, never()).updateSubscription(any(Subscription.class), any(), any());
            verify(billingMetricsService).recordSubscriptionMutation("update", "failed", "cancelled_subscription");
        }

        @Test
        @DisplayName("rejects a blank client idempotency key before ownership or Stripe work")
        void updateSubscription_withBlankIdempotencyKey_rejectsBeforeCollaborators() {
            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(
                    userId, subscriptionId, "price_pro", "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Idempotency-Key must contain");

            verifyNoInteractions(subscriptionStatusRepository, stripeService, mutationCoordinator);
        }

        @Test
        @DisplayName("releases a claimed update for retry when Stripe returns an error")
        void updateSubscription_whenClaimedStripeMutationFails_marksOperationRetryable() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            StripeException unavailable = org.mockito.Mockito.mock(StripeException.class);
            stubOwnedSubscription(localStatus, verifiedSubscription, customer(userId, customerId));
            stubExecuteClaim(SubscriptionMutationType.UPDATE, "price_pro");
            when(stripeService.updateSubscription(
                    verifiedSubscription, "price_pro", "stripe-operation-key"))
                    .thenThrow(unavailable);

            assertThatThrownBy(() -> subscriptionMutationService.updateSubscription(
                    userId, subscriptionId, "price_pro", null))
                    .isInstanceOf(StripeSubscriptionUnavailableException.class)
                    .hasCause(unavailable);

            verify(mutationCoordinator).makeRetryable(operationId(), userId);
            verify(mutationCoordinator, never()).complete(any(), any());
        }
    }

    @Nested
    @DisplayName("Cancel subscription")
    class CancelSubscriptionTests {

        @Test
        @DisplayName("cancels an owned subscription and records the local cancelled state after Stripe succeeds")
        void cancelSubscription_withOwnedSubscription_cancelsAndMarksLocalStatus() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            Subscription cancelledSubscription = stripeSubscription(subscriptionId, customerId, "canceled");
            stubOwnedSubscription(localStatus, verifiedSubscription, customer(userId, customerId));
            stubExecuteClaim(SubscriptionMutationType.CANCEL, null);
            when(stripeService.cancelSubscription(verifiedSubscription, "stripe-operation-key"))
                    .thenReturn(cancelledSubscription);

            Subscription result = subscriptionMutationService.cancelSubscription(userId, subscriptionId);

            assertThat(result).isSameAs(cancelledSubscription);
            verify(stripeService).cancelSubscription(verifiedSubscription, "stripe-operation-key");
            verify(mutationCoordinator).complete(operationId(), userId);
            verify(billingMetricsService).recordSubscriptionMutation("cancel", "allowed", "cancelled");
        }

        @Test
        @DisplayName("returns the existing cancelled subscription without sending a duplicate Stripe cancellation")
        void cancelSubscription_whenAlreadyCancelled_isIdempotent() throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            localStatus.setBlocked(true);
            localStatus.setBlockReason("subscription_cancelled_by_user");
            Subscription cancelledSubscription = stripeSubscription(subscriptionId, customerId, "canceled");
            stubOwnedSubscription(localStatus, cancelledSubscription, customer(userId, customerId));
            stubReplayClaim(SubscriptionMutationType.CANCEL, null, true);

            Subscription result = subscriptionMutationService.cancelSubscription(userId, subscriptionId);

            assertThat(result).isSameAs(cancelledSubscription);
            verify(stripeService, never()).cancelSubscription(any(Subscription.class), any());
            verify(billingMetricsService).recordSubscriptionMutation("cancel", "allowed", "already_cancelled");
        }

        @Test
        @DisplayName("reports a retryable reconciliation failure after Stripe cancels but local persistence fails")
        void cancelSubscription_whenLocalPersistenceFails_doesNotRepeatRemoteCancellationInTheSameRequest()
                throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            Subscription cancelledSubscription = stripeSubscription(subscriptionId, customerId, "canceled");
            stubOwnedSubscription(localStatus, verifiedSubscription, customer(userId, customerId));
            stubExecuteClaim(SubscriptionMutationType.CANCEL, null);
            when(stripeService.cancelSubscription(verifiedSubscription, "stripe-operation-key"))
                    .thenReturn(cancelledSubscription);
            doThrow(new IllegalStateException("database unavailable"))
                    .when(mutationCoordinator).complete(operationId(), userId);

            assertThatThrownBy(() -> subscriptionMutationService.cancelSubscription(userId, subscriptionId))
                    .isInstanceOf(StripeSubscriptionUnavailableException.class);

            verify(stripeService).cancelSubscription(verifiedSubscription, "stripe-operation-key");
            verify(billingMetricsService).recordSubscriptionMutation("cancel", "failed", "local_reconciliation_required");
        }

        @Test
        @DisplayName("preserves a generic authorization denial when the local mapping changes during cancellation")
        void cancelSubscription_whenLocalMappingChangesAfterStripeCancellation_preservesForbiddenResponse()
                throws StripeException {
            SubscriptionStatus localStatus = localStatus(subscriptionId);
            Subscription verifiedSubscription = stripeSubscription(subscriptionId, customerId, "active");
            Subscription cancelledSubscription = stripeSubscription(subscriptionId, customerId, "canceled");
            when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(localStatus));
            when(subscriptionStatusRepository.findAllBySubscriptionId(subscriptionId)).thenReturn(List.of(localStatus));
            when(stripeService.retrieveSubscription(subscriptionId)).thenReturn(verifiedSubscription);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(customer(userId, customerId));
            stubExecuteClaim(SubscriptionMutationType.CANCEL, null);
            when(stripeService.cancelSubscription(verifiedSubscription, "stripe-operation-key"))
                    .thenReturn(cancelledSubscription);
            doThrow(new ForbiddenException("Subscription mutation is not permitted"))
                    .when(mutationCoordinator).complete(operationId(), userId);

            assertThatThrownBy(() -> subscriptionMutationService.cancelSubscription(userId, subscriptionId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Subscription mutation is not permitted");

            verify(stripeService).cancelSubscription(verifiedSubscription, "stripe-operation-key");
            verify(billingMetricsService).recordSubscriptionMutation("cancel", "denied", "local_mapping_changed");
            verify(billingMetricsService, never()).recordSubscriptionMutation(
                    "cancel", "failed", "local_reconciliation_required");
        }

        @Test
        @DisplayName("repairs local state on retry after Stripe cancelled successfully but the first local save failed")
        void cancelSubscription_afterLocalPersistenceFailure_retriesReconciliationWithoutSecondStripeCancellation()
                throws StripeException {
            SubscriptionStatus firstStatus = localStatus(subscriptionId);
            Subscription activeSubscription = stripeSubscription(subscriptionId, customerId, "active");
            Subscription cancelledSubscription = stripeSubscription(subscriptionId, customerId, "canceled");
            Customer owner = customer(userId, customerId);
            when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(firstStatus));
            when(subscriptionStatusRepository.findAllBySubscriptionId(subscriptionId)).thenReturn(List.of(firstStatus));
            when(stripeService.retrieveSubscription(subscriptionId)).thenReturn(activeSubscription, cancelledSubscription);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(owner);
            when(mutationCoordinator.claim(
                    userId, subscriptionId, SubscriptionMutationType.CANCEL, null, null, false))
                    .thenReturn(executeClaim());
            when(mutationCoordinator.claim(
                    userId, subscriptionId, SubscriptionMutationType.CANCEL, null, null, true))
                    .thenReturn(replayClaim());
            when(stripeService.cancelSubscription(activeSubscription, "stripe-operation-key"))
                    .thenReturn(cancelledSubscription);
            doThrow(new IllegalStateException("database unavailable"))
                    .when(mutationCoordinator).complete(operationId(), userId);

            assertThatThrownBy(() -> subscriptionMutationService.cancelSubscription(userId, subscriptionId))
                    .isInstanceOf(StripeSubscriptionUnavailableException.class);

            Subscription retryResult = subscriptionMutationService.cancelSubscription(userId, subscriptionId);

            assertThat(retryResult).isSameAs(cancelledSubscription);
            verify(stripeService).cancelSubscription(activeSubscription, "stripe-operation-key");
            verify(billingMetricsService).recordSubscriptionMutation("cancel", "allowed", "already_cancelled");
        }
    }

    private void stubOwnedSubscription(SubscriptionStatus status, Subscription subscription, Customer customer)
            throws StripeException {
        when(subscriptionStatusRepository.findByUserId(userId)).thenReturn(Optional.of(status));
        when(subscriptionStatusRepository.findAllBySubscriptionId(subscriptionId)).thenReturn(List.of(status));
        when(stripeService.retrieveSubscription(subscriptionId)).thenReturn(subscription);
        when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(customer);
    }

    private void stubExecuteClaim(SubscriptionMutationType type, String targetPriceId) {
        when(mutationCoordinator.claim(
                eq(userId),
                eq(subscriptionId),
                eq(type),
                targetPriceId == null ? isNull() : eq(targetPriceId),
                isNull(),
                eq(false)
        )).thenReturn(executeClaim());
    }

    private void stubReplayClaim(
            SubscriptionMutationType type,
            String targetPriceId,
            boolean remoteAlreadyApplied
    ) {
        when(mutationCoordinator.claim(
                eq(userId),
                eq(subscriptionId),
                eq(type),
                targetPriceId == null ? isNull() : eq(targetPriceId),
                isNull(),
                eq(remoteAlreadyApplied)
        )).thenReturn(replayClaim());
    }

    private SubscriptionMutationClaim executeClaim() {
        return new SubscriptionMutationClaim(
                operationId(), SubscriptionMutationClaim.Action.EXECUTE, "stripe-operation-key");
    }

    private SubscriptionMutationClaim replayClaim() {
        return new SubscriptionMutationClaim(
                operationId(), SubscriptionMutationClaim.Action.REPLAY, "stripe-operation-key");
    }

    private UUID operationId() {
        return UUID.fromString("f4cb7d32-1a62-45f5-bd9d-058199971b8f");
    }

    private SubscriptionStatus localStatus(String id) {
        SubscriptionStatus status = new SubscriptionStatus();
        status.setId(UUID.randomUUID());
        status.setUserId(userId);
        status.setSubscriptionId(id);
        return status;
    }

    private Subscription stripeSubscription(String id, String customer, String status) {
        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setCustomer(customer);
        subscription.setStatus(status);
        return subscription;
    }

    private Customer customer(UUID ownerId, String id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setMetadata(Map.of("userId", ownerId.toString()));
        return customer;
    }

    private InvalidRequestException notFound() {
        return new InvalidRequestException("No such subscription", null, null, null, 404, null);
    }
}
