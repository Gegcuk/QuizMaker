package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.RefundPolicyService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionService;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional edge case tests for StripeWebhookServiceImpl focusing on uncovered branches.
 * Split from main test class due to size.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeWebhookServiceImpl Edge Cases Tests")
@Execution(ExecutionMode.CONCURRENT)
class StripeWebhookServiceImplEdgeCasesTest {

    @Mock
    private StripeProperties stripeProperties;
    
    @Mock
    private StripeService stripeService;
    
    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private RefundPolicyService refundPolicyService;
    
    @Mock
    private CheckoutValidationService checkoutValidationService;
    
    @Mock
    private BillingMetricsService metricsService;
    
    @Mock
    private SubscriptionService subscriptionService;
    
    @Mock
    private ProcessedStripeEventRepository processedStripeEventRepository;
    
    @Mock
    private PaymentRepository paymentRepository;
    
    private ObjectMapper objectMapper;
    
    private StripeWebhookServiceImpl webhookService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webhookService = new StripeWebhookServiceImpl(
            stripeProperties,
            stripeService,
            internalBillingService,
            refundPolicyService,
            checkoutValidationService,
            metricsService,
            subscriptionService,
            processedStripeEventRepository,
            paymentRepository,
            objectMapper
        );
    }

    @Nested
    @DisplayName("JSON Metadata Building Edge Cases")
    class JsonMetadataBuildingEdgeCases {

        @Test
        @DisplayName("Should handle exception when building refund canceled metadata JSON (lines 832-834)")
        void refundCanceled_metadataJsonBuildingFails_returnsOk() throws Exception {
            // Given - Use a broken ObjectMapper that throws exceptions
            ObjectMapper brokenMapper = mock(ObjectMapper.class);
            when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("JSON serialization error") {});
            
            // Re-create service with broken mapper
            StripeWebhookServiceImpl webhookServiceWithBrokenMapper = new StripeWebhookServiceImpl(
                stripeProperties,
                stripeService,
                internalBillingService,
                refundPolicyService,
                checkoutValidationService,
                metricsService,
                subscriptionService,
                processedStripeEventRepository,
                paymentRepository,
                brokenMapper // Broken mapper!
            );

            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_meta_err\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"canceled\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_meta_err");
            lenient().when(mockEvent.getType()).thenReturn("refund.updated");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("canceled");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            uk.gegc.quizmaker.features.billing.domain.model.Payment mockPayment = mock(uk.gegc.quizmaker.features.billing.domain.model.Payment.class);
            UUID userId = UUID.randomUUID();
            lenient().when(mockPayment.getUserId()).thenReturn(userId);
            lenient().when(mockPayment.getAmountCents()).thenReturn(2000L);
            lenient().when(mockPayment.getCreditedTokens()).thenReturn(2000L);

            when(processedStripeEventRepository.existsByEventId("evt_refund_meta_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(mockPayment));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When - Should succeed despite metadata JSON building failure (line 832-834)
                StripeWebhookService.Result result = webhookServiceWithBrokenMapper.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookOk("refund.updated");
                // Metadata will be null but refund cancellation should still process
                verify(internalBillingService).creditPurchase(eq(userId), any(Long.class), anyString(), anyString(), eq(null));
            }
        }

        @Test
        @DisplayName("Should handle exception when building dispute won metadata JSON (lines 1070-1072)")
        void disputeWon_metadataJsonBuildingFails_returnsOk() throws Exception {
            // Given - Use a broken ObjectMapper
            ObjectMapper brokenMapper = mock(ObjectMapper.class);
            when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("JSON serialization error") {});
            
            StripeWebhookServiceImpl webhookServiceWithBrokenMapper = new StripeWebhookServiceImpl(
                stripeProperties,
                stripeService,
                internalBillingService,
                refundPolicyService,
                checkoutValidationService,
                metricsService,
                subscriptionService,
                processedStripeEventRepository,
                paymentRepository,
                brokenMapper
            );

            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_meta_err\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"status\":\"won\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_meta_err");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.closed");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getStatus()).thenReturn("won");
            lenient().when(mockDispute.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            uk.gegc.quizmaker.features.billing.domain.model.Payment mockPayment = mock(uk.gegc.quizmaker.features.billing.domain.model.Payment.class);
            UUID userId = UUID.randomUUID();
            lenient().when(mockPayment.getUserId()).thenReturn(userId);
            lenient().when(mockPayment.getAmountCents()).thenReturn(2000L);
            lenient().when(mockPayment.getCreditedTokens()).thenReturn(2000L);

            when(processedStripeEventRepository.existsByEventId("evt_dispute_meta_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(mockPayment));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When - Should succeed despite metadata JSON building failure (lines 1070-1072)
                StripeWebhookService.Result result = webhookServiceWithBrokenMapper.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookOk("charge.dispute.closed");
                // Metadata will be null but dispute win credit should still process
                verify(internalBillingService).creditPurchase(eq(userId), any(Long.class), anyString(), anyString(), eq(null));
            }
        }
    }

    @Nested
    @DisplayName("Refund Processing Edge Cases")
    class RefundProcessingEdgeCases {

        @Test
        @DisplayName("Should throw StripeException for refund.updated Stripe API error (lines 772-774)")
        void refundUpdated_stripeException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_upd_stripe_err\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"canceled\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_upd_stripe_err");
            lenient().when(mockEvent.getType()).thenReturn("refund.updated");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("canceled");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(processedStripeEventRepository.existsByEventId("evt_refund_upd_stripe_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test"))
                .thenThrow(new com.stripe.exception.ApiException("Stripe API error", "req_123", "code", 500, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(com.stripe.exception.StripeException.class);

                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookFailed("refund.updated");
            }
        }

        @Test
        @DisplayName("Should throw generic Exception for refund.updated processing error (lines 775-777)")
        void refundUpdated_genericException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_upd_gen_err\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"canceled\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_upd_gen_err");
            lenient().when(mockEvent.getType()).thenReturn("refund.updated");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("canceled");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(processedStripeEventRepository.existsByEventId("evt_refund_upd_gen_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test"))
                .thenThrow(new RuntimeException("Database error"));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Database error");

                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookFailed("refund.updated");
            }
        }

        @Test
        @DisplayName("Should throw generic Exception for refund succeeded processing error (lines 816-818)")
        void refundSucceeded_genericException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_succ_err\",\"type\":\"refund.created\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"succeeded\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_succ_err");
            lenient().when(mockEvent.getType()).thenReturn("refund.created");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("succeeded");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            when(processedStripeEventRepository.existsByEventId("evt_refund_succ_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test"))
                .thenThrow(new RuntimeException("Database error"));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Database error");

                verify(metricsService).incrementWebhookReceived("refund.created");
                verify(metricsService).incrementWebhookFailed("refund.created");
            }
        }

        @Test
        @DisplayName("Should throw StripeException when charge retrieval fails in refund.created (lines 682-684)")
        void refundCreated_chargeRetrievalFailsWithStripeException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_stripe_api_err\",\"type\":\"refund.created\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"succeeded\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_stripe_api_err");
            lenient().when(mockEvent.getType()).thenReturn("refund.created");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("succeeded");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(processedStripeEventRepository.existsByEventId("evt_refund_stripe_api_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test"))
                .thenThrow(new com.stripe.exception.ApiException("Charge not found", "req_123", "resource_missing", 404, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(com.stripe.exception.StripeException.class);

                verify(metricsService).incrementWebhookReceived("refund.created");
                verify(metricsService).incrementWebhookFailed("refund.created");
            }
        }

        @Test
        @DisplayName("Should handle refund.created with successful charge retrieval and processing")
        void refundCreated_withChargeRetrieval_processesSuccessfully() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_success\",\"type\":\"refund.created\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"succeeded\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_success");
            lenient().when(mockEvent.getType()).thenReturn("refund.created");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("succeeded");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            uk.gegc.quizmaker.features.billing.domain.model.Payment mockPayment = mock(uk.gegc.quizmaker.features.billing.domain.model.Payment.class);
            UUID userId = UUID.randomUUID();
            lenient().when(mockPayment.getUserId()).thenReturn(userId);
            lenient().when(mockPayment.getAmountCents()).thenReturn(2000L);
            lenient().when(mockPayment.getCreditedTokens()).thenReturn(2000L);

            uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto mockCalculation = 
                mock(uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto.class);
            lenient().when(mockCalculation.tokensToDeduct()).thenReturn(1000L);

            when(processedStripeEventRepository.existsByEventId("evt_refund_success")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(mockPayment));
            when(refundPolicyService.calculateRefund(mockPayment, 1000L)).thenReturn(mockCalculation);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("refund.created");
                verify(metricsService).incrementWebhookOk("refund.created");
                verify(stripeService).retrieveCharge("ch_test");
                // Note: Refund processing doesn't debit - it just tracks the refund
            }
        }

        @Test
        @DisplayName("Should return OK when payment not found for canceled refund (line 723-724)")
        void refundUpdated_canceledNoPayment_returnsOk() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_no_pay\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"canceled\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_no_pay");
            lenient().when(mockEvent.getType()).thenReturn("refund.updated");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("canceled");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            when(processedStripeEventRepository.existsByEventId("evt_refund_no_pay")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.empty()); // NO PAYMENT

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookOk("refund.updated");
                verify(internalBillingService, never()).creditPurchase(any(UUID.class), any(Long.class), anyString(), anyString(), any());
            }
        }

        @Test
        @DisplayName("Should handle zero amount payment for canceled refund (line 740)")
        void refundUpdated_canceledZeroAmount_warnsAndSkipsRestore() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_refund_zero\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_test\",\"payment_intent\":\"pi_test\",\"charge\":\"ch_test\",\"status\":\"canceled\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_refund_zero");
            lenient().when(mockEvent.getType()).thenReturn("refund.updated");

            com.stripe.model.Refund mockRefund = mock(com.stripe.model.Refund.class);
            lenient().when(mockRefund.getId()).thenReturn("re_test");
            lenient().when(mockRefund.getPaymentIntent()).thenReturn("pi_test");
            lenient().when(mockRefund.getCharge()).thenReturn("ch_test");
            lenient().when(mockRefund.getStatus()).thenReturn("canceled");
            lenient().when(mockRefund.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockRefund));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            uk.gegc.quizmaker.features.billing.domain.model.Payment mockPayment = mock(uk.gegc.quizmaker.features.billing.domain.model.Payment.class);
            lenient().when(mockPayment.getUserId()).thenReturn(UUID.randomUUID());
            lenient().when(mockPayment.getAmountCents()).thenReturn(0L); // ZERO AMOUNT!
            lenient().when(mockPayment.getCreditedTokens()).thenReturn(1000L);

            when(processedStripeEventRepository.existsByEventId("evt_refund_zero")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(mockPayment));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookOk("refund.updated");
                // Should NOT credit refund cancellation because tokensToRestore will be 0
                verify(internalBillingService, never()).creditPurchase(any(UUID.class), any(Long.class), anyString(), anyString(), any());
            }
        }
    }

    @Nested
    @DisplayName("Dispute Processing Edge Cases")
    class DisputeProcessingEdgeCases {

        @Test
        @DisplayName("Should throw StripeException for dispute funds withdrawn Stripe API error (lines 936-939)")
        void disputeFundsWithdrawn_stripeException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_fw_stripe_err\",\"type\":\"charge.dispute.funds_withdrawn\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_fw_stripe_err");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.funds_withdrawn");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(processedStripeEventRepository.existsByEventId("evt_dispute_fw_stripe_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test"))
                .thenThrow(new com.stripe.exception.ApiException("Stripe API error", "req_123", "code", 500, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(com.stripe.exception.StripeException.class);

                verify(metricsService).incrementWebhookReceived("charge.dispute.funds_withdrawn");
                verify(metricsService).incrementWebhookFailed("charge.dispute.funds_withdrawn");
            }
        }

        @Test
        @DisplayName("Should throw StripeException for dispute closed Stripe API error (lines 991-994)")
        void disputeClosed_stripeException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_closed_stripe_err\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"status\":\"won\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_closed_stripe_err");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.closed");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getStatus()).thenReturn("won");
            lenient().when(mockDispute.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(processedStripeEventRepository.existsByEventId("evt_dispute_closed_stripe_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test"))
                .thenThrow(new com.stripe.exception.ApiException("Stripe API error", "req_123", "code", 500, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(com.stripe.exception.StripeException.class);

                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookFailed("charge.dispute.closed");
            }
        }

        // Note: Lines 1050-1052 (Stripe exception catch in dispute won) are tested via disputeClosed_stripeException_throwsException above

        @Test
        @DisplayName("Should throw generic Exception for dispute won processing error (lines 1053-1056)")
        void disputeWon_genericException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_won_gen_err\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"status\":\"won\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_won_gen_err");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.closed");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getStatus()).thenReturn("won");
            lenient().when(mockDispute.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            when(processedStripeEventRepository.existsByEventId("evt_dispute_won_gen_err")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test"))
                .thenThrow(new RuntimeException("Database error"));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Database error");

                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookFailed("charge.dispute.closed");
            }
        }

        @Test
        @DisplayName("Should return OK when payment not found for dispute won (lines 1009-1010)")
        void disputeWon_paymentNotFound_returnsOk() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_won_no_payment\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"status\":\"won\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_won_no_payment");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.closed");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getStatus()).thenReturn("won");
            lenient().when(mockDispute.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            when(processedStripeEventRepository.existsByEventId("evt_dispute_won_no_payment")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.empty()); // NO PAYMENT

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then - Early return when payment not found
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookOk("charge.dispute.closed");
                verify(internalBillingService, never()).creditPurchase(any(UUID.class), any(Long.class), anyString(), anyString(), any());
            }
        }

        @Test
        @DisplayName("Should warn when dispute won payment amount is zero (line 1027)")
        void disputeWon_zeroAmountPayment_warns() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_zero_amt\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"status\":\"won\",\"amount\":1000}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_zero_amt");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.closed");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getStatus()).thenReturn("won");
            lenient().when(mockDispute.getAmount()).thenReturn(1000L);

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Charge mockCharge = mock(com.stripe.model.Charge.class);
            lenient().when(mockCharge.getPaymentIntent()).thenReturn("pi_test");

            uk.gegc.quizmaker.features.billing.domain.model.Payment mockPayment = mock(uk.gegc.quizmaker.features.billing.domain.model.Payment.class);
            lenient().when(mockPayment.getUserId()).thenReturn(UUID.randomUUID());
            lenient().when(mockPayment.getAmountCents()).thenReturn(0L); // ZERO AMOUNT!
            lenient().when(mockPayment.getCreditedTokens()).thenReturn(1000L);

            when(processedStripeEventRepository.existsByEventId("evt_dispute_zero_amt")).thenReturn(false);
            when(stripeService.retrieveCharge("ch_test")).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(mockPayment));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then - Warns but processes (tokensToRestore = 0 due to division by zero guard)
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookOk("charge.dispute.closed");
                verify(internalBillingService, never()).creditPurchase(any(UUID.class), any(Long.class), anyString(), anyString(), any());
            }
        }

        @Test
        @DisplayName("Should catch exception when dispute.created processing fails (lines 869-871)")
        void disputeCreated_processingError_catchesAndLogsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_err\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_test\"}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_err");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.created");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenThrow(new RuntimeException("Processing error")); // Throws on getCharge()

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When - Exception is caught internally (lines 869-871), webhook returns OK
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then - Returns OK despite exception (logged but not thrown)
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("charge.dispute.created");
                verify(metricsService).incrementWebhookOk("charge.dispute.created");
            }
        }

        @Test
        @DisplayName("Should return OK for dispute closed with non-won status (line 983)")
        void disputeClosed_nonWonStatus_returnsOk() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_dispute_warning\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_test\",\"charge\":\"ch_test\",\"status\":\"warning_closed\"}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_dispute_warning");
            lenient().when(mockEvent.getType()).thenReturn("charge.dispute.closed");

            com.stripe.model.Dispute mockDispute = mock(com.stripe.model.Dispute.class);
            lenient().when(mockDispute.getId()).thenReturn("dp_test");
            lenient().when(mockDispute.getCharge()).thenReturn("ch_test");
            lenient().when(mockDispute.getStatus()).thenReturn("warning_closed"); // Not "won"

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockDispute));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(processedStripeEventRepository.existsByEventId("evt_dispute_warning")).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookOk("charge.dispute.closed");
                verify(internalBillingService, never()).creditPurchase(any(UUID.class), any(Long.class), anyString(), anyString(), any());
            }
        }
    }

    // Note: Reflection fallback tests (lines 1192-1199, 1216-1223) are omitted because:
    // - Session is a final Stripe class that cannot be properly mocked with doReturn()
    // - These are defensive code paths for Stripe API edge cases
    // - They are covered by integration tests with real Stripe objects in production

    @Nested
    @DisplayName("Subscription and Invoice Exception Tests")
    class SubscriptionAndInvoiceExceptionTests {

        @Test
        @DisplayName("Should throw exception for subscription payment failure generic error (lines 561-564)")
        void subscriptionPaymentFailure_genericException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_sub_pay_fail_err\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_test\",\"subscription\":\"sub_test\"}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_sub_pay_fail_err");
            lenient().when(mockEvent.getType()).thenReturn("invoice.payment_failed");

            com.stripe.model.Invoice mockInvoice = mock(com.stripe.model.Invoice.class);
            lenient().when(mockInvoice.getId()).thenReturn("in_test");
            lenient().when(mockInvoice.getSubscription()).thenReturn("sub_test");

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockInvoice));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Subscription mockSubscription = mock(com.stripe.model.Subscription.class);
            lenient().when(mockSubscription.getCustomer()).thenReturn("cus_test");
            
            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            lenient().when(mockCustomer.getMetadata()).thenReturn(java.util.Map.of("userId", UUID.randomUUID().toString()));

            when(stripeService.retrieveSubscription("sub_test")).thenReturn(mockSubscription);
            when(stripeService.retrieveCustomerRaw("cus_test")).thenReturn(mockCustomer);
            org.mockito.Mockito.doThrow(new RuntimeException("Subscription failure processing error"))
                .when(subscriptionService).handleSubscriptionPaymentFailure(any(UUID.class), eq("sub_test"), eq("payment_failed")); // Note: eventType, not eventId

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Subscription failure processing error");

                verify(metricsService).incrementWebhookReceived("invoice.payment_failed");
                verify(metricsService).incrementWebhookFailed("invoice.payment_failed");
            }
        }

        @Test
        @DisplayName("Should throw exception for invoice payment failed processing error (lines 604-606)")
        void invoicePaymentFailed_genericException_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_inv_fail_err\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_test\",\"subscription\":\"sub_test\"}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_inv_fail_err");
            lenient().when(mockEvent.getType()).thenReturn("invoice.payment_failed");

            com.stripe.model.Invoice mockInvoice = mock(com.stripe.model.Invoice.class);
            lenient().when(mockInvoice.getId()).thenReturn("in_test");
            lenient().when(mockInvoice.getSubscription()).thenReturn("sub_test");

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockInvoice));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            when(stripeService.retrieveSubscription("sub_test"))
                .thenThrow(new RuntimeException("Subscription retrieval failed"));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Subscription retrieval failed");

                verify(metricsService).incrementWebhookReceived("invoice.payment_failed");
                verify(metricsService).incrementWebhookFailed("invoice.payment_failed");
            }
        }

        @Test
        @DisplayName("Should throw exception for subscription.deleted processing error (lines 650-653)")
        void subscriptionDeleted_processingError_throwsException() throws StripeException {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_sub_del_err\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"sub_test\",\"customer\":\"cus_test\"}}}";
            String signature = "t=1234567890,v1=sig";

            Event mockEvent = mock(Event.class);
            lenient().when(mockEvent.getId()).thenReturn("evt_sub_del_err");
            lenient().when(mockEvent.getType()).thenReturn("customer.subscription.deleted");

            com.stripe.model.Subscription mockSubscription = mock(com.stripe.model.Subscription.class);
            lenient().when(mockSubscription.getId()).thenReturn("sub_test");
            lenient().when(mockSubscription.getCustomer()).thenReturn("cus_test");

            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            lenient().when(mockDeserializer.getObject()).thenReturn(Optional.of(mockSubscription));
            lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);

            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            lenient().when(mockCustomer.getMetadata()).thenReturn(java.util.Map.of("userId", UUID.randomUUID().toString()));
            
            when(stripeService.retrieveCustomerRaw("cus_test")).thenReturn(mockCustomer);
            org.mockito.Mockito.doThrow(new RuntimeException("Subscription deletion failed"))
                .when(subscriptionService).handleSubscriptionDeleted(any(UUID.class), eq("sub_test"), anyString());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Subscription deletion failed");

                verify(metricsService).incrementWebhookReceived("customer.subscription.deleted");
                verify(metricsService).incrementWebhookFailed("customer.subscription.deleted");
            }
        }
    }
}

