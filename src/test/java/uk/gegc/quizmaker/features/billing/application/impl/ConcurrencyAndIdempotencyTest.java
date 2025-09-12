package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto;
import uk.gegc.quizmaker.features.billing.application.RefundPolicyService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionService;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Concurrency & Idempotency Tests")
class ConcurrencyAndIdempotencyTest {

    @Mock
    private StripeProperties stripeProperties;
    @Mock
    private StripeService stripeService;
    @Mock
    private CheckoutValidationService checkoutValidationService;
    @Mock
    private InternalBillingService internalBillingService;
    @Mock
    private RefundPolicyService refundPolicyService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ProcessedStripeEventRepository processedStripeEventRepository;
    @Mock
    private BillingMetricsService billingMetricsService;
    @Mock
    private ObjectMapper objectMapper;

    private StripeWebhookServiceImpl webhookService;

    private String testEventId;
    private String testSessionId;
    private String testPaymentIntentId;
    private UUID testUserId;
    private UUID testPackId;

    @BeforeEach
    void setUp() {
        webhookService = new StripeWebhookServiceImpl(
            stripeProperties,
            stripeService,
            internalBillingService,
            refundPolicyService,
            checkoutValidationService,
            billingMetricsService,
            subscriptionService,
            processedStripeEventRepository,
            paymentRepository,
            objectMapper
        );

        testEventId = "evt_test_" + System.currentTimeMillis();
        testSessionId = "cs_test_" + System.currentTimeMillis();
        testPaymentIntentId = "pi_test_" + System.currentTimeMillis();
        testUserId = UUID.randomUUID();
        testPackId = UUID.randomUUID();

        // Common setup
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
    }

    @Nested
    @DisplayName("Race Condition Tests")
    class RaceConditionTests {

        @Test
        @DisplayName("Two threads process same eventId: only one credits tokens; other returns DUPLICATE")
        void shouldHandleRaceConditionOnSameEventId() throws Exception {
            // Given
            String sharedEventId = "evt_race_test_" + System.currentTimeMillis();
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                sharedEventId, testSessionId);
            String signature = "t=1234567890,v1=signature";

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(testSessionId);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn(testPaymentIntentId);
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", testUserId.toString());
            metadata.put("packId", testPackId.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);
            
            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(1000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");

            // Mock event
            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(sharedEventId);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);
            
            // Mock EventDataObjectDeserializer as fallback
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(Optional.of(mockSession));

            // Mock validation result
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPack = mock(ProductPack.class);
            
            when(mockPack.getId()).thenReturn(testPackId);
            when(mockPack.getName()).thenReturn("Test Pack");
            when(mockPack.getStripePriceId()).thenReturn("price_test");
            when(mockPack.getPriceCents()).thenReturn(1000L);
            when(mockPack.getCurrency()).thenReturn("usd");
            when(mockPack.getTokens()).thenReturn(500L);
            
            when(mockValidationResult.primaryPack()).thenReturn(mockPack);
            when(mockValidationResult.additionalPacks()).thenReturn(null);
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(sharedEventId))
                .thenReturn(false)  // First thread sees it doesn't exist
                .thenReturn(true);  // Second thread sees it exists
            when(stripeService.retrieveSession(testSessionId, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);
            when(paymentRepository.findByStripeSessionId(testSessionId)).thenReturn(Optional.empty());

            // Mock successful payment save
            Payment savedPayment = new Payment();
            savedPayment.setId(UUID.randomUUID());
            savedPayment.setUserId(testUserId);
            savedPayment.setStatus(PaymentStatus.SUCCEEDED);
            savedPayment.setStripeSessionId(testSessionId);
            savedPayment.setAmountCents(1000L);
            savedPayment.setCurrency("usd");
            savedPayment.setCreditedTokens(500L);
            when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

            // Mock successful processed event save
            ProcessedStripeEvent savedEvent = new ProcessedStripeEvent();
            savedEvent.setEventId(sharedEventId);
            when(processedStripeEventRepository.save(any(ProcessedStripeEvent.class))).thenReturn(savedEvent);

            // Track credit calls
            doNothing().when(internalBillingService).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());

            // When - simulate concurrent processing
            ExecutorService executor = Executors.newFixedThreadPool(2);
            
            CompletableFuture<StripeWebhookService.Result> future1 = CompletableFuture.supplyAsync(() -> {
                try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                    webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                        .thenReturn(mockEvent);
                    
                    return webhookService.process(payload, signature);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);

            CompletableFuture<StripeWebhookService.Result> future2 = CompletableFuture.supplyAsync(() -> {
                try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                    webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                        .thenReturn(mockEvent);
                    
                    return webhookService.process(payload, signature);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);

            // Then
            StripeWebhookService.Result result1 = future1.get(5, TimeUnit.SECONDS);
            StripeWebhookService.Result result2 = future2.get(5, TimeUnit.SECONDS);

            executor.shutdown();

            // Verify only one thread succeeded and one returned DUPLICATE
            assertThat(result1).isIn(StripeWebhookService.Result.OK, StripeWebhookService.Result.DUPLICATE);
            assertThat(result2).isIn(StripeWebhookService.Result.OK, StripeWebhookService.Result.DUPLICATE);
            assertThat(result1).isNotEqualTo(result2); // One OK, one DUPLICATE

            // Verify processed event was saved only once
            verify(processedStripeEventRepository, times(1)).save(any(ProcessedStripeEvent.class));
        }
    }

    @Nested
    @DisplayName("Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("Same event processed multiple times returns DUPLICATE")
        void shouldReturnDuplicateForSameEvent() throws Exception {
            // Given
            String eventId = "evt_idempotency_test_" + System.currentTimeMillis();
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                eventId, testSessionId);
            String signature = "t=1234567890,v1=signature";

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(testSessionId);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn(testPaymentIntentId);
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", testUserId.toString());
            metadata.put("packId", testPackId.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);
            
            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(1000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");

            // Mock event
            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(eventId);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);
            
            // Mock EventDataObjectDeserializer as fallback
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(Optional.of(mockSession));

            // Mock validation result
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPack = mock(ProductPack.class);
            
            when(mockPack.getId()).thenReturn(testPackId);
            when(mockPack.getName()).thenReturn("Test Pack");
            when(mockPack.getStripePriceId()).thenReturn("price_test");
            when(mockPack.getPriceCents()).thenReturn(1000L);
            when(mockPack.getCurrency()).thenReturn("usd");
            when(mockPack.getTokens()).thenReturn(500L);
            
            when(mockValidationResult.primaryPack()).thenReturn(mockPack);
            when(mockValidationResult.additionalPacks()).thenReturn(null);
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(eventId))
                .thenReturn(false)  // First attempt sees it doesn't exist
                .thenReturn(true)   // Second attempt sees it exists
                .thenReturn(true);  // Third attempt sees it exists
            when(stripeService.retrieveSession(testSessionId, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);
            when(paymentRepository.findByStripeSessionId(testSessionId)).thenReturn(Optional.empty());

            // Mock successful payment save
            Payment savedPayment = new Payment();
            savedPayment.setId(UUID.randomUUID());
            savedPayment.setUserId(testUserId);
            savedPayment.setStatus(PaymentStatus.SUCCEEDED);
            savedPayment.setStripeSessionId(testSessionId);
            savedPayment.setAmountCents(1000L);
            savedPayment.setCurrency("usd");
            savedPayment.setCreditedTokens(500L);
            when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

            // Mock successful processed event save
            ProcessedStripeEvent savedEvent = new ProcessedStripeEvent();
            savedEvent.setEventId(eventId);
            when(processedStripeEventRepository.save(any(ProcessedStripeEvent.class))).thenReturn(savedEvent);

            // Track credit calls
            doNothing().when(internalBillingService).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());

            // When - process the same event multiple times
            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);
                
                // First attempt should succeed
                StripeWebhookService.Result result1 = webhookService.process(payload, signature);
                assertThat(result1).isEqualTo(StripeWebhookService.Result.OK);
                
                // Second attempt should return DUPLICATE
                StripeWebhookService.Result result2 = webhookService.process(payload, signature);
                assertThat(result2).isEqualTo(StripeWebhookService.Result.DUPLICATE);
                
                // Third attempt should also return DUPLICATE
                StripeWebhookService.Result result3 = webhookService.process(payload, signature);
                assertThat(result3).isEqualTo(StripeWebhookService.Result.DUPLICATE);
            }

            // Then - verify processed event was saved only once
            verify(processedStripeEventRepository, times(1)).save(any(ProcessedStripeEvent.class));
        }
    }

    @Nested
    @DisplayName("Out-of-Order Events")
    class OutOfOrderEventsTests {

        @Test
        @DisplayName("refund.updated before refund.created: succeeded path still processes correctly")
        void shouldHandleRefundUpdatedBeforeRefundCreated() throws Exception {
            // Given - refund.updated event comes before refund.created
            String refundId = "re_test_refund_" + System.currentTimeMillis();
            String chargeId = "ch_test_charge_" + System.currentTimeMillis();
            String paymentIntentId = "pi_test_payment_intent_" + System.currentTimeMillis();
            String eventId1 = "evt_test_1_" + System.currentTimeMillis();
            String eventId2 = "evt_test_2_" + System.currentTimeMillis();
            long refundAmountCents = 500L;

            // Mock existing payment
            Payment existingPayment = new Payment();
            existingPayment.setId(UUID.randomUUID());
            existingPayment.setUserId(testUserId);
            existingPayment.setStatus(PaymentStatus.SUCCEEDED);
            existingPayment.setStripePaymentIntentId(paymentIntentId);
            existingPayment.setAmountCents(1000L);
            existingPayment.setCurrency("usd");
            existingPayment.setCreditedTokens(500L);

            // Mock refund object for both events
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("succeeded");
            when(mockRefund.getAmount()).thenReturn(refundAmountCents);

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Setup mocks for refund.updated event (comes first)
            when(processedStripeEventRepository.existsByEventId(eventId1)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(existingPayment));
            when(processedStripeEventRepository.save(any(ProcessedStripeEvent.class))).thenReturn(new ProcessedStripeEvent());

            // Mock refund policy
            RefundCalculationDto mockCalculation = mock(RefundCalculationDto.class);
            when(mockCalculation.tokensToDeduct()).thenReturn(250L);
            when(mockCalculation.refundAmountCents()).thenReturn(refundAmountCents);
            when(refundPolicyService.calculateRefund(existingPayment, refundAmountCents)).thenReturn(mockCalculation);
            doNothing().when(refundPolicyService).processRefund(eq(existingPayment), eq(mockCalculation), eq(refundId), eq(eventId1));

            // Create refund.updated event (comes first)
            String refundUpdatedPayload = String.format("""
                {
                    "id": "%s",
                    "type": "refund.updated",
                    "data": {
                        "object": {
                            "id": "%s",
                            "charge": "%s",
                            "status": "succeeded",
                            "amount": %d
                        }
                    }
                }
                """, eventId1, refundId, chargeId, refundAmountCents);

            Event mockEvent1 = mock(Event.class);
            when(mockEvent1.getId()).thenReturn(eventId1);
            when(mockEvent1.getType()).thenReturn("refund.updated");
            when(mockEvent1.toJson()).thenReturn(refundUpdatedPayload);

            EventDataObjectDeserializer mockDeserializer1 = mock(EventDataObjectDeserializer.class);
            when(mockDeserializer1.getObject()).thenReturn(Optional.of(mockRefund));
            when(mockEvent1.getDataObjectDeserializer()).thenReturn(mockDeserializer1);

            // When - process refund.updated first
            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(refundUpdatedPayload, "signature", "whsec_test_secret"))
                    .thenReturn(mockEvent1);

                StripeWebhookService.Result result1 = webhookService.process(refundUpdatedPayload, "signature");
                assertThat(result1).isEqualTo(StripeWebhookService.Result.OK);
            }

            // Setup mocks for refund.created event (comes second)
            when(processedStripeEventRepository.existsByEventId(eventId2)).thenReturn(false);

            // Create refund.created event (comes second)
            String refundCreatedPayload = String.format("""
                {
                    "id": "%s",
                    "type": "refund.created",
                    "data": {
                        "object": {
                            "id": "%s",
                            "charge": "%s",
                            "status": "succeeded",
                            "amount": %d
                        }
                    }
                }
                """, eventId2, refundId, chargeId, refundAmountCents);

            Event mockEvent2 = mock(Event.class);
            when(mockEvent2.getId()).thenReturn(eventId2);
            when(mockEvent2.getType()).thenReturn("refund.created");
            when(mockEvent2.toJson()).thenReturn(refundCreatedPayload);

            EventDataObjectDeserializer mockDeserializer2 = mock(EventDataObjectDeserializer.class);
            when(mockDeserializer2.getObject()).thenReturn(Optional.of(mockRefund));
            when(mockEvent2.getDataObjectDeserializer()).thenReturn(mockDeserializer2);

            // When - process refund.created second
            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(refundCreatedPayload, "signature", "whsec_test_secret"))
                    .thenReturn(mockEvent2);

                StripeWebhookService.Result result2 = webhookService.process(refundCreatedPayload, "signature");
                assertThat(result2).isEqualTo(StripeWebhookService.Result.OK);
            }

            // Then - verify both events were processed correctly
            verify(refundPolicyService, times(2)).calculateRefund(existingPayment, refundAmountCents);
            verify(refundPolicyService, times(2)).processRefund(eq(existingPayment), eq(mockCalculation), eq(refundId), anyString());
            verify(processedStripeEventRepository, times(2)).save(any(ProcessedStripeEvent.class));
        }

        @Test
        @DisplayName("Multiple partial refunds + dispute mix: interleavings remain idempotent")
        void shouldHandleMultiplePartialRefundsAndDisputeMixIdempotently() throws Exception {
            // Given - complex scenario with multiple partial refunds and disputes
            String chargeId = "ch_test_charge_" + System.currentTimeMillis();
            String paymentIntentId = "pi_test_payment_intent_" + System.currentTimeMillis();
            String refundId1 = "re_test_refund_1_" + System.currentTimeMillis();
            String refundId2 = "re_test_refund_2_" + System.currentTimeMillis();
            String disputeId = "dp_test_dispute_" + System.currentTimeMillis();
            String eventId1 = "evt_test_1_" + System.currentTimeMillis();
            String eventId2 = "evt_test_2_" + System.currentTimeMillis();
            String eventId3 = "evt_test_3_" + System.currentTimeMillis();
            String eventId4 = "evt_test_4_" + System.currentTimeMillis();
            long partialRefundAmount1 = 300L;
            long partialRefundAmount2 = 200L;
            long disputeAmount = 500L;

            // Mock existing payment
            Payment existingPayment = new Payment();
            existingPayment.setId(UUID.randomUUID());
            existingPayment.setUserId(testUserId);
            existingPayment.setStatus(PaymentStatus.SUCCEEDED);
            existingPayment.setStripePaymentIntentId(paymentIntentId);
            existingPayment.setAmountCents(1000L);
            existingPayment.setCurrency("usd");
            existingPayment.setCreditedTokens(500L);

            // Mock refunds
            Refund mockRefund1 = mock(Refund.class);
            when(mockRefund1.getId()).thenReturn(refundId1);
            when(mockRefund1.getCharge()).thenReturn(chargeId);
            when(mockRefund1.getStatus()).thenReturn("succeeded");
            when(mockRefund1.getAmount()).thenReturn(partialRefundAmount1);

            Refund mockRefund2 = mock(Refund.class);
            when(mockRefund2.getId()).thenReturn(refundId2);
            when(mockRefund2.getCharge()).thenReturn(chargeId);
            when(mockRefund2.getStatus()).thenReturn("succeeded");
            when(mockRefund2.getAmount()).thenReturn(partialRefundAmount2);

            // Mock dispute
            Dispute mockDispute = mock(Dispute.class);
            when(mockDispute.getId()).thenReturn(disputeId);
            when(mockDispute.getCharge()).thenReturn(chargeId);
            when(mockDispute.getAmount()).thenReturn(disputeAmount);
            when(mockDispute.getStatus()).thenReturn("funds_withdrawn");

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Setup common mocks
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(existingPayment));
            when(processedStripeEventRepository.save(any(ProcessedStripeEvent.class))).thenReturn(new ProcessedStripeEvent());

            // Mock refund calculations
            RefundCalculationDto mockCalculation1 = mock(RefundCalculationDto.class);
            when(mockCalculation1.tokensToDeduct()).thenReturn(150L);
            when(mockCalculation1.refundAmountCents()).thenReturn(partialRefundAmount1);
            when(refundPolicyService.calculateRefund(existingPayment, partialRefundAmount1)).thenReturn(mockCalculation1);

            RefundCalculationDto mockCalculation2 = mock(RefundCalculationDto.class);
            when(mockCalculation2.tokensToDeduct()).thenReturn(100L);
            when(mockCalculation2.refundAmountCents()).thenReturn(partialRefundAmount2);
            when(refundPolicyService.calculateRefund(existingPayment, partialRefundAmount2)).thenReturn(mockCalculation2);

            RefundCalculationDto mockDisputeCalculation = mock(RefundCalculationDto.class);
            when(mockDisputeCalculation.tokensToDeduct()).thenReturn(250L);
            when(mockDisputeCalculation.refundAmountCents()).thenReturn(disputeAmount);
            when(refundPolicyService.calculateRefund(existingPayment, disputeAmount)).thenReturn(mockDisputeCalculation);

            // Mock refund policy calls
            doNothing().when(refundPolicyService).processRefund(eq(existingPayment), any(RefundCalculationDto.class), anyString(), anyString());

            // Test sequence: refund1 → dispute → refund2 → cancel refund1
            // This tests that the system remains idempotent despite complex interleavings

            // 1. First partial refund
            when(processedStripeEventRepository.existsByEventId(eventId1)).thenReturn(false);

            String refund1Payload = String.format("""
                {
                    "id": "%s",
                    "type": "refund.created",
                    "data": {
                        "object": {
                            "id": "%s",
                            "charge": "%s",
                            "status": "succeeded",
                            "amount": %d
                        }
                    }
                }
                """, eventId1, refundId1, chargeId, partialRefundAmount1);

            Event mockEvent1 = mock(Event.class);
            when(mockEvent1.getId()).thenReturn(eventId1);
            when(mockEvent1.getType()).thenReturn("refund.created");
            when(mockEvent1.toJson()).thenReturn(refund1Payload);

            EventDataObjectDeserializer mockDeserializer1 = mock(EventDataObjectDeserializer.class);
            when(mockDeserializer1.getObject()).thenReturn(Optional.of(mockRefund1));
            when(mockEvent1.getDataObjectDeserializer()).thenReturn(mockDeserializer1);

            // 2. Dispute (funds_withdrawn)
            when(processedStripeEventRepository.existsByEventId(eventId2)).thenReturn(false);

            String disputePayload = String.format("""
                {
                    "id": "%s",
                    "type": "charge.dispute.funds_withdrawn",
                    "data": {
                        "object": {
                            "id": "%s",
                            "charge": "%s",
                            "amount": %d,
                            "status": "funds_withdrawn"
                        }
                    }
                }
                """, eventId2, disputeId, chargeId, disputeAmount);

            Event mockEvent2 = mock(Event.class);
            when(mockEvent2.getId()).thenReturn(eventId2);
            when(mockEvent2.getType()).thenReturn("charge.dispute.funds_withdrawn");
            when(mockEvent2.toJson()).thenReturn(disputePayload);

            EventDataObjectDeserializer mockDeserializer2 = mock(EventDataObjectDeserializer.class);
            when(mockDeserializer2.getObject()).thenReturn(Optional.of(mockDispute));
            when(mockEvent2.getDataObjectDeserializer()).thenReturn(mockDeserializer2);

            // 3. Second partial refund
            when(processedStripeEventRepository.existsByEventId(eventId3)).thenReturn(false);

            String refund2Payload = String.format("""
                {
                    "id": "%s",
                    "type": "refund.created",
                    "data": {
                        "object": {
                            "id": "%s",
                            "charge": "%s",
                            "status": "succeeded",
                            "amount": %d
                        }
                    }
                }
                """, eventId3, refundId2, chargeId, partialRefundAmount2);

            Event mockEvent3 = mock(Event.class);
            when(mockEvent3.getId()).thenReturn(eventId3);
            when(mockEvent3.getType()).thenReturn("refund.created");
            when(mockEvent3.toJson()).thenReturn(refund2Payload);

            EventDataObjectDeserializer mockDeserializer3 = mock(EventDataObjectDeserializer.class);
            when(mockDeserializer3.getObject()).thenReturn(Optional.of(mockRefund2));
            when(mockEvent3.getDataObjectDeserializer()).thenReturn(mockDeserializer3);

            // 4. Cancel first refund
            when(processedStripeEventRepository.existsByEventId(eventId4)).thenReturn(false);
            
            // Create a separate mock for the canceled refund
            Refund mockRefund1Canceled = mock(Refund.class);
            when(mockRefund1Canceled.getId()).thenReturn(refundId1);
            when(mockRefund1Canceled.getCharge()).thenReturn(chargeId);
            when(mockRefund1Canceled.getStatus()).thenReturn("canceled");
            when(mockRefund1Canceled.getAmount()).thenReturn(partialRefundAmount1);

            String cancelRefundPayload = String.format("""
                {
                    "id": "%s",
                    "type": "refund.updated",
                    "data": {
                        "object": {
                            "id": "%s",
                            "charge": "%s",
                            "status": "canceled",
                            "amount": %d
                        }
                    }
                }
                """, eventId4, refundId1, chargeId, partialRefundAmount1);

            Event mockEvent4 = mock(Event.class);
            when(mockEvent4.getId()).thenReturn(eventId4);
            when(mockEvent4.getType()).thenReturn("refund.updated");
            when(mockEvent4.toJson()).thenReturn(cancelRefundPayload);

            EventDataObjectDeserializer mockDeserializer4 = mock(EventDataObjectDeserializer.class);
            when(mockDeserializer4.getObject()).thenReturn(Optional.of(mockRefund1Canceled));
            when(mockEvent4.getDataObjectDeserializer()).thenReturn(mockDeserializer4);

            // Mock credit purchase for refund cancellation
            doNothing().when(internalBillingService).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());

            // When - process all events in sequence
            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(anyString(), eq("signature"), eq("whsec_test_secret")))
                    .thenReturn(mockEvent1)
                    .thenReturn(mockEvent2)
                    .thenReturn(mockEvent3)
                    .thenReturn(mockEvent4);

                // Process all events
                StripeWebhookService.Result result1 = webhookService.process(refund1Payload, "signature");
                StripeWebhookService.Result result2 = webhookService.process(disputePayload, "signature");
                StripeWebhookService.Result result3 = webhookService.process(refund2Payload, "signature");
                StripeWebhookService.Result result4 = webhookService.process(cancelRefundPayload, "signature");

                // Then - verify all events were processed successfully
                assertThat(result1).isEqualTo(StripeWebhookService.Result.OK);
                assertThat(result2).isEqualTo(StripeWebhookService.Result.OK);
                assertThat(result3).isEqualTo(StripeWebhookService.Result.OK);
                assertThat(result4).isEqualTo(StripeWebhookService.Result.OK);
            }

            // Verify idempotency - each refund/dispute should be processed exactly once
            verify(refundPolicyService, times(1)).processRefund(eq(existingPayment), eq(mockCalculation1), eq(refundId1), eq(eventId1));
            verify(refundPolicyService, times(1)).processRefund(eq(existingPayment), eq(mockDisputeCalculation), eq(disputeId), eq(eventId2));
            verify(refundPolicyService, times(1)).processRefund(eq(existingPayment), eq(mockCalculation2), eq(refundId2), eq(eventId3));
            verify(internalBillingService, times(1)).creditPurchase(eq(testUserId), eq(150L), eq("refund-canceled:" + refundId1), eq(refundId1), any());
            verify(processedStripeEventRepository, times(4)).save(any(ProcessedStripeEvent.class));
        }
    }
}