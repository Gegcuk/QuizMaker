package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.CheckoutSessionSettlementCommand;
import uk.gegc.quizmaker.features.billing.application.CheckoutSessionSettlementService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.CheckoutSessionSettlement;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;
import uk.gegc.quizmaker.features.billing.infra.repository.CheckoutSessionSettlementRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Checkout session settlement")
class CheckoutSessionSettlementServiceImplTest {

    private static final String SESSION_ID = "cs_session_123";
    private static final String EVENT_ID = "evt_123";
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID PACK_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CheckoutSessionSettlementRepository settlementRepository;

    @Mock
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Mock
    private InternalBillingService internalBillingService;

    private CheckoutSessionSettlementService service;

    @BeforeEach
    void setUp() {
        service = new CheckoutSessionSettlementServiceImpl(
                paymentRepository,
                settlementRepository,
                processedStripeEventRepository,
                internalBillingService
        );
    }

    @Test
    @DisplayName("keeps an unpaid completion pending without a credit")
    void keepsUnpaidCompletionPendingWithoutCredit() {
        Payment payment = pendingPayment();
        givenLockedPayment(payment);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(false,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.PENDING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(internalBillingService, never()).creditPurchase(any(), anyLong(), any(), any(), any());
        verify(paymentRepository).save(payment);
        ArgumentCaptor<ProcessedStripeEvent> eventCaptor = eventCaptor();
        verify(processedStripeEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventId()).isEqualTo(EVENT_ID);
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("credits a paid session with a session-scoped ledger key")
    void creditsPaidSessionWithSessionScopedLedgerKey() {
        Payment payment = pendingPayment();
        givenLockedPayment(payment);
        when(settlementRepository.existsById(SESSION_ID)).thenReturn(false);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.CREDITED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getStripePaymentIntentId()).isEqualTo("pi_123");
        assertThat(payment.getStripeCustomerId()).isEqualTo("cus_123");
        ArgumentCaptor<CheckoutSessionSettlement> settlementCaptor = settlementCaptor();
        verify(settlementRepository).save(settlementCaptor.capture());
        assertThat(settlementCaptor.getValue().getStripeSessionId()).isEqualTo(SESSION_ID);
        assertThat(settlementCaptor.getValue().getPaymentId()).isEqualTo(payment.getId());
        verify(internalBillingService).creditPurchase(
                USER_ID,
                500L,
                "checkout-session:" + SESSION_ID,
                PACK_ID.toString(),
                "{\"source\":\"stripe\"}"
        );
        verify(paymentRepository).save(payment);
        verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
    }

    @Test
    @DisplayName("suppresses a later paid event after the session was already credited")
    void suppressesLaterPaidEventAfterSessionWasAlreadyCredited() {
        Payment payment = pendingPayment();
        payment.setStatus(PaymentStatus.SUCCEEDED);
        givenLockedPayment(payment);
        when(settlementRepository.existsById(SESSION_ID)).thenReturn(true);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.ALREADY_SETTLED);
        verifyNoInteractions(internalBillingService);
        verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
    }

    @Test
    @DisplayName("credits after an earlier unpaid completion when async success becomes paid")
    void creditsAfterEarlierUnpaidCompletionWhenAsyncSuccessBecomesPaid() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByStripeSessionIdForUpdate(SESSION_ID)).thenReturn(Optional.of(payment));
        when(processedStripeEventRepository.existsByEventId("evt_completed")).thenReturn(false);
        when(processedStripeEventRepository.existsByEventId("evt_async_succeeded")).thenReturn(false);
        when(settlementRepository.existsById(SESSION_ID)).thenReturn(false);

        CheckoutSessionSettlementService.SettlementResult completionResult = service.settle(command("evt_completed", false,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));
        CheckoutSessionSettlementService.SettlementResult asyncSuccessResult = service.settle(command("evt_async_succeeded", true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));

        assertThat(completionResult).isEqualTo(CheckoutSessionSettlementService.SettlementResult.PENDING);
        assertThat(asyncSuccessResult).isEqualTo(CheckoutSessionSettlementService.SettlementResult.CREDITED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(internalBillingService).creditPurchase(eq(USER_ID), eq(500L), eq("checkout-session:" + SESSION_ID),
                eq(PACK_ID.toString()), eq("{\"source\":\"stripe\"}"));
    }

    @Test
    @DisplayName("marks an unpaid async failure failed but never overwrites a success")
    void marksUnpaidAsyncFailureFailed() {
        Payment payment = pendingPayment();
        givenLockedPayment(payment);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(false,
                CheckoutSessionSettlementCommand.UnpaidDisposition.MARK_FAILED));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(internalBillingService);
    }

    @Test
    @DisplayName("does not overwrite an already settled payment when an async failure arrives later")
    void doesNotOverwriteAlreadySettledPaymentWhenAsyncFailureArrivesLater() {
        Payment payment = pendingPayment();
        payment.setStatus(PaymentStatus.SUCCEEDED);
        givenLockedPayment(payment);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(false,
                CheckoutSessionSettlementCommand.UnpaidDisposition.MARK_FAILED));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.ALREADY_SETTLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verifyNoInteractions(internalBillingService);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("credits a paid session when a legacy payment intent event marked it succeeded without a settlement marker")
    void creditsPaidSessionWhenSucceededPaymentHasNoSettlementMarker() {
        Payment payment = pendingPayment();
        payment.setStatus(PaymentStatus.SUCCEEDED);
        givenLockedPayment(payment);
        when(settlementRepository.existsById(SESSION_ID)).thenReturn(false);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.CREDITED);
        verify(internalBillingService).creditPurchase(eq(USER_ID), eq(500L), eq("checkout-session:" + SESSION_ID),
                eq(PACK_ID.toString()), eq("{\"source\":\"stripe\"}"));
    }

    @Test
    @DisplayName("returns duplicate without mutating a session when the same Stripe event was processed")
    void returnsDuplicateWithoutMutatingSessionWhenSameStripeEventWasProcessed() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByStripeSessionIdForUpdate(SESSION_ID)).thenReturn(Optional.of(payment));
        when(processedStripeEventRepository.existsByEventId(EVENT_ID)).thenReturn(true);

        CheckoutSessionSettlementService.SettlementResult result = service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING));

        assertThat(result).isEqualTo(CheckoutSessionSettlementService.SettlementResult.DUPLICATE_EVENT);
        verifyNoInteractions(settlementRepository, internalBillingService);
        verify(paymentRepository, never()).save(any());
        verify(processedStripeEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects a checkout event with no server-created payment without crediting")
    void rejectsCheckoutEventWithoutServerCreatedPayment() {
        when(paymentRepository.findByStripeSessionIdForUpdate(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING)))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("No server-created payment exists");

        verifyNoInteractions(settlementRepository, internalBillingService, processedStripeEventRepository);
    }

    @Test
    @DisplayName("rejects a paid Stripe session that does not match the server-created payment snapshot")
    void rejectsPaymentSnapshotMismatchWithoutCreditingOrRecordingTheEvent() {
        Payment payment = pendingPayment();
        payment.setAmountCents(999L);
        givenLockedPayment(payment);

        assertThatThrownBy(() -> service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING)))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("does not match the server-created payment");

        verifyNoInteractions(settlementRepository, internalBillingService);
        verify(processedStripeEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects a Stripe payment intent that conflicts with the server-created payment")
    void rejectsConflictingStripePaymentIntentWithoutCreditingOrRecordingTheEvent() {
        Payment payment = pendingPayment();
        payment.setStripePaymentIntentId("pi_server_created");
        givenLockedPayment(payment);

        assertThatThrownBy(() -> service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING)))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("payment intent does not match the server-created payment");

        verifyNoInteractions(settlementRepository, internalBillingService);
        verify(processedStripeEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects a settlement marker without a final payment state")
    void rejectsSettlementMarkerWithoutFinalPaymentState() {
        Payment payment = pendingPayment();
        givenLockedPayment(payment);
        when(settlementRepository.existsById(SESSION_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.settle(command(true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING)))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("settlement exists without a final payment state");

        verifyNoInteractions(internalBillingService);
        verify(processedStripeEventRepository, never()).save(any());
    }

    private void givenLockedPayment(Payment payment) {
        when(paymentRepository.findByStripeSessionIdForUpdate(SESSION_ID)).thenReturn(Optional.of(payment));
        when(processedStripeEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);
    }

    private static Payment pendingPayment() {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setUserId(USER_ID);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setStripeSessionId(SESSION_ID);
        payment.setPackId(PACK_ID);
        payment.setAmountCents(2500L);
        payment.setCurrency("eur");
        payment.setCreditedTokens(500L);
        return payment;
    }

    private static CheckoutSessionSettlementCommand command(
            boolean paid,
            CheckoutSessionSettlementCommand.UnpaidDisposition unpaidDisposition
    ) {
        return command(EVENT_ID, paid, unpaidDisposition);
    }

    private static CheckoutSessionSettlementCommand command(
            String eventId,
            boolean paid,
            CheckoutSessionSettlementCommand.UnpaidDisposition unpaidDisposition
    ) {
        return new CheckoutSessionSettlementCommand(
                eventId,
                SESSION_ID,
                "pi_123",
                "cus_123",
                USER_ID,
                PACK_ID,
                2500L,
                "EUR",
                500L,
                "{\"source\":\"stripe\"}",
                paid,
                unpaidDisposition
        );
    }

    private static ArgumentCaptor<ProcessedStripeEvent> eventCaptor() {
        return ArgumentCaptor.forClass(ProcessedStripeEvent.class);
    }

    private static ArgumentCaptor<CheckoutSessionSettlement> settlementCaptor() {
        return ArgumentCaptor.forClass(CheckoutSessionSettlement.class);
    }
}
