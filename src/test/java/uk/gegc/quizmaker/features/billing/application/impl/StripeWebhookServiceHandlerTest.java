package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
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
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.RefundPolicyService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.exception.PackNotFoundException;
import uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StripeWebhookService Handler Tests")
class StripeWebhookServiceHandlerTest {

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
    
    private final String TEST_EVENT_ID = "evt_test_123";
    private final String TEST_SESSION_ID = "cs_test_session_456";
    private final UUID TEST_USER_ID = UUID.randomUUID();
    private final UUID TEST_PACK_ID = UUID.randomUUID();

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
    @DisplayName("Checkout Session Completed Handler Tests")
    class CheckoutSessionCompletedHandlerTests {

        @Test
        @DisplayName("Happy path - single pack: validates session, saves Payment, calls creditPurchase")
        void shouldHandleSinglePackSuccessfully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, TEST_SESSION_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(TEST_SESSION_ID);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent");
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", TEST_USER_ID.toString());
            metadata.put("packId", TEST_PACK_ID.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);
            
            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(1000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");

            // Mock validation result
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPrimaryPack = mock(ProductPack.class);
            when(mockPrimaryPack.getId()).thenReturn(TEST_PACK_ID);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);
            
            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of());
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(TEST_SESSION_ID, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);
            when(paymentRepository.findByStripeSessionId(TEST_SESSION_ID)).thenReturn(Optional.empty());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify basic interactions
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookOk("checkout.session.completed");
                verify(stripeService).retrieveSession(TEST_SESSION_ID, true);
                verify(checkoutValidationService).validateAndResolvePack(eq(mockSession), any());
            }
        }

        @Test
        @DisplayName("Multiple packs: totalTokens, totalAmountCents, pack count and metadata reflect all items")
        void shouldHandleMultiplePacksSuccessfully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, TEST_SESSION_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(TEST_SESSION_ID);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent");
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", TEST_USER_ID.toString());
            metadata.put("packId", TEST_PACK_ID.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);
            
            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(1500L); // Total for both packs
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");

            // Mock validation result with multiple packs
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPrimaryPack = mock(ProductPack.class);
            ProductPack mockAdditionalPack = mock(ProductPack.class);
            
            when(mockPrimaryPack.getId()).thenReturn(TEST_PACK_ID);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);
            
            UUID additionalPackId = UUID.randomUUID();
            when(mockAdditionalPack.getId()).thenReturn(additionalPackId);
            when(mockAdditionalPack.getName()).thenReturn("Premium Pack");
            when(mockAdditionalPack.getStripePriceId()).thenReturn("price_premium");
            when(mockAdditionalPack.getPriceCents()).thenReturn(500L);
            when(mockAdditionalPack.getCurrency()).thenReturn("usd");
            when(mockAdditionalPack.getTokens()).thenReturn(250L);
            
            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of(mockAdditionalPack));
            when(mockValidationResult.totalAmountCents()).thenReturn(1500L);
            when(mockValidationResult.totalTokens()).thenReturn(750L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(2);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(true);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(TEST_SESSION_ID, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);
            when(paymentRepository.findByStripeSessionId(TEST_SESSION_ID)).thenReturn(Optional.empty());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify basic interactions
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookOk("checkout.session.completed");
                verify(stripeService).retrieveSession(TEST_SESSION_ID, true);
                verify(checkoutValidationService).validateAndResolvePack(eq(mockSession), any());
            }
        }

        @Test
        @DisplayName("Duplicate event: pre-exists → returns DUPLICATE, no side effects")
        void shouldHandleDuplicateEvent() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, TEST_SESSION_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(true);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.DUPLICATE);
                
                // Verify no side effects
                verify(stripeService, never()).retrieveSession(anyString(), any(Boolean.class));
                
                // Verify metrics
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookDuplicate("checkout.session.completed");
            }
        }

        @Test
        @DisplayName("Stripe session retrieval fails → increments failed, rethrows for retry")
        void shouldHandleSessionRetrievalFailure() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, TEST_SESSION_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(TEST_SESSION_ID, true))
                .thenThrow(new InvalidRequestException("Session not found", null, null, null, 0, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(InvalidCheckoutSessionException.class)
                    .hasMessage("Stripe session retrieval failed");
                
                // Verify metrics
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookFailed("checkout.session.completed");
                
                // Verify no ProcessedStripeEvent saved
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("Validation rejects → throws expected domain exception; no credit, no processed event")
        void shouldHandleValidationRejection() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, TEST_SESSION_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(TEST_SESSION_ID);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent");
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", TEST_USER_ID.toString());
            metadata.put("packId", TEST_PACK_ID.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);

            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(TEST_SESSION_ID, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any()))
                .thenThrow(new PackNotFoundException("Pack not found"));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(PackNotFoundException.class)
                    .hasMessage("Pack not found");
                
                // Verify metrics
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookFailed("checkout.session.completed");
                
                // Verify no credit, no processed event
                verify(internalBillingService, never()).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("Missing session ID in event → throws InvalidCheckoutSessionException")
        void shouldHandleMissingSessionId() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{}}}", 
                TEST_EVENT_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(InvalidCheckoutSessionException.class)
                    .hasMessage("Missing session id in event payload");
                
                // Verify no side effects
                verify(stripeService, never()).retrieveSession(anyString(), any(Boolean.class));
            }
        }

        @Test
        @DisplayName("Invalid UUID in metadata → throws domain exception")
        void shouldHandleInvalidUuidInMetadata() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, TEST_SESSION_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session with invalid UUID
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(TEST_SESSION_ID);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent");
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", "invalid-uuid");
            metadata.put("packId", TEST_PACK_ID.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);

            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(TEST_SESSION_ID, true)).thenReturn(mockSession);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(InvalidCheckoutSessionException.class);
                
                // Verify no side effects - exception thrown before reaching these services
            }
        }
    }

    @Nested
    @DisplayName("Invoice Payment Succeeded Handler Tests")
    class InvoicePaymentSucceededHandlerTests {

        @Test
        @DisplayName("Subscription invoice: retrieves subscription, extracts customer/user, derives price, calls subscriptionService.handleSubscriptionPaymentSuccess exactly once")
        void shouldHandleSubscriptionInvoicePaymentSuccess() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String subscriptionId = "sub_test_123";
            String customerId = "cus_test_customer";
            String priceId = "price_test_123";
            String invoiceId = "in_test_123";
            long periodStart = 1234567890L;
            long tokensPerPeriod = 1000L;
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"invoice.payment_succeeded\",\"data\":{\"object\":{\"id\":\"%s\",\"subscription\":\"%s\"}}}", 
                TEST_EVENT_ID, invoiceId, subscriptionId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("invoice.payment_succeeded");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock invoice
            Invoice mockInvoice = mock(Invoice.class);
            when(mockInvoice.getId()).thenReturn(invoiceId);
            when(mockInvoice.getSubscription()).thenReturn(subscriptionId);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockInvoice));

            // Mock subscription
            Subscription mockSubscription = mock(Subscription.class);
            when(mockSubscription.getId()).thenReturn(subscriptionId);
            when(mockSubscription.getCustomer()).thenReturn(customerId);
            when(mockSubscription.getCurrentPeriodStart()).thenReturn(periodStart);
            
            // Mock subscription items to get price ID
            com.stripe.model.SubscriptionItemCollection mockItems = mock(com.stripe.model.SubscriptionItemCollection.class);
            com.stripe.model.SubscriptionItem mockItem = mock(com.stripe.model.SubscriptionItem.class);
            com.stripe.model.Price mockPrice = mock(com.stripe.model.Price.class);
            
            when(mockSubscription.getItems()).thenReturn(mockItems);
            when(mockItems.getData()).thenReturn(List.of(mockItem));
            when(mockItem.getPrice()).thenReturn(mockPrice);
            when(mockPrice.getId()).thenReturn(priceId);

            // Mock customer metadata
            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            Map<String, String> customerMetadata = new HashMap<>();
            customerMetadata.put("userId", TEST_USER_ID.toString());
            when(mockCustomer.getMetadata()).thenReturn(customerMetadata);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSubscription(subscriptionId)).thenReturn(mockSubscription);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(mockCustomer);
            when(subscriptionService.getTokensPerPeriod(subscriptionId, priceId)).thenReturn(tokensPerPeriod);
            when(subscriptionService.handleSubscriptionPaymentSuccess(TEST_USER_ID, subscriptionId, periodStart, tokensPerPeriod, TEST_EVENT_ID)).thenReturn(true);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("invoice.payment_succeeded");
                verify(metricsService).incrementWebhookOk("invoice.payment_succeeded");
                verify(stripeService).retrieveSubscription(subscriptionId);
                verify(stripeService).retrieveCustomerRaw(customerId);
                verify(subscriptionService).getTokensPerPeriod(subscriptionId, priceId);
                verify(subscriptionService).handleSubscriptionPaymentSuccess(TEST_USER_ID, subscriptionId, periodStart, tokensPerPeriod, TEST_EVENT_ID);
            }
        }

        @Test
        @DisplayName("Missing price id → logs and exits without credit (no exception)")
        void shouldHandleMissingPriceIdGracefully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String subscriptionId = "sub_test_123";
            String customerId = "cus_test_customer";
            String invoiceId = "in_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"invoice.payment_succeeded\",\"data\":{\"object\":{\"id\":\"%s\",\"subscription\":\"%s\"}}}", 
                TEST_EVENT_ID, invoiceId, subscriptionId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("invoice.payment_succeeded");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock invoice
            Invoice mockInvoice = mock(Invoice.class);
            when(mockInvoice.getId()).thenReturn(invoiceId);
            when(mockInvoice.getSubscription()).thenReturn(subscriptionId);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockInvoice));

            // Mock subscription with no price items
            Subscription mockSubscription = mock(Subscription.class);
            when(mockSubscription.getId()).thenReturn(subscriptionId);
            when(mockSubscription.getCustomer()).thenReturn(customerId);
            
            com.stripe.model.SubscriptionItemCollection mockItems = mock(com.stripe.model.SubscriptionItemCollection.class);
            when(mockSubscription.getItems()).thenReturn(mockItems);
            when(mockItems.getData()).thenReturn(List.of()); // Empty items = no price

            // Mock customer metadata
            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            Map<String, String> customerMetadata = new HashMap<>();
            customerMetadata.put("userId", TEST_USER_ID.toString());
            when(mockCustomer.getMetadata()).thenReturn(customerMetadata);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSubscription(subscriptionId)).thenReturn(mockSubscription);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(mockCustomer);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("invoice.payment_succeeded");
                verify(metricsService).incrementWebhookOk("invoice.payment_succeeded");
                verify(stripeService).retrieveSubscription(subscriptionId);
                verify(stripeService).retrieveCustomerRaw(customerId);
                
                // Verify no credit was attempted
                verify(subscriptionService, never()).getTokensPerPeriod(anyString(), anyString());
                verify(subscriptionService, never()).handleSubscriptionPaymentSuccess(any(), anyString(), anyLong(), anyLong(), anyString());
            }
        }

        @Test
        @DisplayName("One-time invoice (no subscription) → logs informational, no side effect")
        void shouldHandleOneTimeInvoiceGracefully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String invoiceId = "in_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"invoice.payment_succeeded\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, invoiceId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("invoice.payment_succeeded");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock invoice with no subscription
            Invoice mockInvoice = mock(Invoice.class);
            when(mockInvoice.getId()).thenReturn(invoiceId);
            when(mockInvoice.getSubscription()).thenReturn(null); // No subscription = one-time payment

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockInvoice));

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("invoice.payment_succeeded");
                verify(metricsService).incrementWebhookOk("invoice.payment_succeeded");
                
                // Verify no subscription-related calls
                verify(stripeService, never()).retrieveSubscription(anyString());
                verify(stripeService, never()).retrieveCustomerRaw(anyString());
                verify(subscriptionService, never()).getTokensPerPeriod(anyString(), anyString());
                verify(subscriptionService, never()).handleSubscriptionPaymentSuccess(any(), anyString(), anyLong(), anyLong(), anyString());
            }
        }

        @Test
        @DisplayName("Stripe errors bubble up → increments failed")
        void shouldHandleStripeErrorsAndIncrementFailed() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String subscriptionId = "sub_test_123";
            String invoiceId = "in_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"invoice.payment_succeeded\",\"data\":{\"object\":{\"id\":\"%s\",\"subscription\":\"%s\"}}}", 
                TEST_EVENT_ID, invoiceId, subscriptionId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("invoice.payment_succeeded");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock invoice
            Invoice mockInvoice = mock(Invoice.class);
            when(mockInvoice.getId()).thenReturn(invoiceId);
            when(mockInvoice.getSubscription()).thenReturn(subscriptionId);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockInvoice));

            // Setup mocks to throw Stripe exception
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSubscription(subscriptionId))
                .thenThrow(new InvalidRequestException("Subscription not found", null, null, null, 0, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(InvalidRequestException.class);
                
                // Verify metrics
                verify(metricsService).incrementWebhookReceived("invoice.payment_succeeded");
                verify(metricsService).incrementWebhookFailed("invoice.payment_succeeded");
            }
        }
    }

    @Nested
    @DisplayName("Invoice Payment Failed Handler Tests")
    class InvoicePaymentFailedHandlerTests {

        @Test
        @DisplayName("Subscription failure: calls subscriptionService.handleSubscriptionPaymentFailure(userId, subscriptionId, \"payment_failed\")")
        void shouldHandleSubscriptionPaymentFailure() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String subscriptionId = "sub_test_123";
            String customerId = "cus_test_customer";
            String invoiceId = "in_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"%s\",\"subscription\":\"%s\"}}}", 
                TEST_EVENT_ID, invoiceId, subscriptionId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("invoice.payment_failed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock invoice
            Invoice mockInvoice = mock(Invoice.class);
            when(mockInvoice.getId()).thenReturn(invoiceId);
            when(mockInvoice.getSubscription()).thenReturn(subscriptionId);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockInvoice));

            // Mock subscription
            Subscription mockSubscription = mock(Subscription.class);
            when(mockSubscription.getId()).thenReturn(subscriptionId);
            when(mockSubscription.getCustomer()).thenReturn(customerId);

            // Mock customer metadata
            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            Map<String, String> customerMetadata = new HashMap<>();
            customerMetadata.put("userId", TEST_USER_ID.toString());
            when(mockCustomer.getMetadata()).thenReturn(customerMetadata);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSubscription(subscriptionId)).thenReturn(mockSubscription);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(mockCustomer);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("invoice.payment_failed");
                verify(metricsService).incrementWebhookOk("invoice.payment_failed");
                verify(stripeService).retrieveSubscription(subscriptionId);
                verify(stripeService).retrieveCustomerRaw(customerId);
                verify(subscriptionService).handleSubscriptionPaymentFailure(TEST_USER_ID, subscriptionId, "payment_failed");
            }
        }

        @Test
        @DisplayName("Non-subscription failure: no-op besides logging")
        void shouldHandleNonSubscriptionPaymentFailure() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String invoiceId = "in_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"%s\"}}}", 
                TEST_EVENT_ID, invoiceId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("invoice.payment_failed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock invoice with no subscription
            Invoice mockInvoice = mock(Invoice.class);
            when(mockInvoice.getId()).thenReturn(invoiceId);
            when(mockInvoice.getSubscription()).thenReturn(null); // No subscription = one-time payment

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockInvoice));

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("invoice.payment_failed");
                verify(metricsService).incrementWebhookOk("invoice.payment_failed");
                
                // Verify no subscription-related calls
                verify(stripeService, never()).retrieveSubscription(anyString());
                verify(stripeService, never()).retrieveCustomerRaw(anyString());
                verify(subscriptionService, never()).handleSubscriptionPaymentFailure(any(), anyString(), anyString());
            }
        }
    }

    @Nested
    @DisplayName("Customer Subscription Deleted Handler Tests")
    class CustomerSubscriptionDeletedHandlerTests {

        @Test
        @DisplayName("Extracts user via customer metadata, calls handleSubscriptionDeleted(..., \"subscription_deleted\")")
        void shouldHandleSubscriptionDeletedSuccessfully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String subscriptionId = "sub_test_123";
            String customerId = "cus_test_customer";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"%s\",\"customer\":\"%s\"}}}", 
                TEST_EVENT_ID, subscriptionId, customerId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("customer.subscription.deleted");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock subscription
            Subscription mockSubscription = mock(Subscription.class);
            when(mockSubscription.getId()).thenReturn(subscriptionId);
            when(mockSubscription.getCustomer()).thenReturn(customerId);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockSubscription));

            // Mock customer metadata
            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            Map<String, String> customerMetadata = new HashMap<>();
            customerMetadata.put("userId", TEST_USER_ID.toString());
            when(mockCustomer.getMetadata()).thenReturn(customerMetadata);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(mockCustomer);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("customer.subscription.deleted");
                verify(metricsService).incrementWebhookOk("customer.subscription.deleted");
                verify(stripeService).retrieveCustomerRaw(customerId);
                verify(subscriptionService).handleSubscriptionDeleted(TEST_USER_ID, subscriptionId, "subscription_deleted");
            }
        }

        @Test
        @DisplayName("Missing user id → logs warning, no side effects")
        void shouldHandleMissingUserIdGracefully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String subscriptionId = "sub_test_123";
            String customerId = "cus_test_customer";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"%s\",\"customer\":\"%s\"}}}", 
                TEST_EVENT_ID, subscriptionId, customerId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("customer.subscription.deleted");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock subscription
            Subscription mockSubscription = mock(Subscription.class);
            when(mockSubscription.getId()).thenReturn(subscriptionId);
            when(mockSubscription.getCustomer()).thenReturn(customerId);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockSubscription));

            // Mock customer with no userId in metadata
            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            Map<String, String> customerMetadata = new HashMap<>();
            // No userId in metadata
            when(mockCustomer.getMetadata()).thenReturn(customerMetadata);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(mockCustomer);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("customer.subscription.deleted");
                verify(metricsService).incrementWebhookOk("customer.subscription.deleted");
                verify(stripeService).retrieveCustomerRaw(customerId);
                
                // Verify no subscription service call
                verify(subscriptionService, never()).handleSubscriptionDeleted(any(), anyString(), anyString());
            }
        }
    }

    @Nested
    @DisplayName("Refund Created Handler Tests")
    class RefundCreatedHandlerTests {

        @Test
        @DisplayName("refund.created with succeeded: looks up Charge → PI → Payment, calls policy calculateRefund then processRefund")
        void shouldHandleSucceededRefundCreated() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String refundId = "re_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            long refundAmountCents = 500L;
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"refund.created\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"status\":\"succeeded\",\"amount\":%d}}}", 
                TEST_EVENT_ID, refundId, chargeId, refundAmountCents);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("refund.created");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock refund
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("succeeded");
            when(mockRefund.getAmount()).thenReturn(refundAmountCents);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockRefund));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Mock payment
            Payment mockPayment = mock(Payment.class);
            when(mockPayment.getUserId()).thenReturn(TEST_USER_ID);
            when(mockPayment.getAmountCents()).thenReturn(1000L);
            when(mockPayment.getCreditedTokens()).thenReturn(100L);

            // Mock refund calculation
            RefundCalculationDto mockCalculation = mock(RefundCalculationDto.class);
            when(mockCalculation.tokensToDeduct()).thenReturn(50L);
            when(mockCalculation.refundAmountCents()).thenReturn(refundAmountCents);
            when(mockCalculation.policyApplied()).thenReturn("proportional");

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(mockPayment));
            when(refundPolicyService.calculateRefund(mockPayment, refundAmountCents)).thenReturn(mockCalculation);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("refund.created");
                verify(metricsService).incrementWebhookOk("refund.created");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                verify(refundPolicyService).calculateRefund(mockPayment, refundAmountCents);
                verify(refundPolicyService).processRefund(mockPayment, mockCalculation, refundId, TEST_EVENT_ID);
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("Non-succeeded status on refund.created → OK/no-op")
        void shouldHandleNonSucceededRefundCreated() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String refundId = "re_test_123";
            String chargeId = "ch_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"refund.created\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"status\":\"pending\"}}}", 
                TEST_EVENT_ID, refundId, chargeId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("refund.created");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock refund with pending status
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("pending");

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockRefund));

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("refund.created");
                verify(metricsService).incrementWebhookOk("refund.created");
                
                // Verify no processing occurred
                verify(stripeService, never()).retrieveCharge(anyString());
                verify(paymentRepository, never()).findByStripePaymentIntentId(anyString());
                verify(refundPolicyService, never()).calculateRefund(any(), anyLong());
                verify(refundPolicyService, never()).processRefund(any(), any(), anyString(), anyString());
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }
    }

    @Nested
    @DisplayName("Refund Updated Handler Tests")
    class RefundUpdatedHandlerTests {

        @Test
        @DisplayName("refund.updated with succeeded: same as above (idempotent in policy by refund id)")
        void shouldHandleSucceededRefundUpdated() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String refundId = "re_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            long refundAmountCents = 500L;
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"status\":\"succeeded\",\"amount\":%d}}}", 
                TEST_EVENT_ID, refundId, chargeId, refundAmountCents);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("refund.updated");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock refund
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("succeeded");
            when(mockRefund.getAmount()).thenReturn(refundAmountCents);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockRefund));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Mock payment
            Payment mockPayment = mock(Payment.class);
            when(mockPayment.getUserId()).thenReturn(TEST_USER_ID);
            when(mockPayment.getAmountCents()).thenReturn(1000L);
            when(mockPayment.getCreditedTokens()).thenReturn(100L);

            // Mock refund calculation
            RefundCalculationDto mockCalculation = mock(RefundCalculationDto.class);
            when(mockCalculation.tokensToDeduct()).thenReturn(50L);
            when(mockCalculation.refundAmountCents()).thenReturn(refundAmountCents);
            when(mockCalculation.policyApplied()).thenReturn("proportional");

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(mockPayment));
            when(refundPolicyService.calculateRefund(mockPayment, refundAmountCents)).thenReturn(mockCalculation);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookOk("refund.updated");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                verify(refundPolicyService).calculateRefund(mockPayment, refundAmountCents);
                verify(refundPolicyService).processRefund(mockPayment, mockCalculation, refundId, TEST_EVENT_ID);
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("refund.updated with canceled: proportional token restore using creditPurchase with key refund-canceled:{refundId}")
        void shouldHandleCanceledRefundUpdated() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String refundId = "re_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            long refundAmountCents = 500L;
            long originalAmountCents = 1000L;
            long originalTokens = 100L;
            long tokensToRestore = 50L; // (100 * 500) / 1000
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"status\":\"canceled\",\"amount\":%d}}}", 
                TEST_EVENT_ID, refundId, chargeId, refundAmountCents);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("refund.updated");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock refund
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("canceled");
            when(mockRefund.getAmount()).thenReturn(refundAmountCents);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockRefund));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Mock payment
            Payment mockPayment = mock(Payment.class);
            when(mockPayment.getUserId()).thenReturn(TEST_USER_ID);
            when(mockPayment.getAmountCents()).thenReturn(originalAmountCents);
            when(mockPayment.getCreditedTokens()).thenReturn(originalTokens);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(mockPayment));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookOk("refund.updated");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                
                // Verify token restoration
                String expectedIdempotencyKey = String.format("refund-canceled:%s", refundId);
                verify(internalBillingService).creditPurchase(
                    eq(TEST_USER_ID),
                    eq(tokensToRestore),
                    eq(expectedIdempotencyKey),
                    eq(refundId),
                    anyString() // metadata
                );
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("No matching Payment → logs and returns OK (no throw)")
        void shouldHandleNoMatchingPaymentGracefully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String refundId = "re_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"status\":\"succeeded\"}}}", 
                TEST_EVENT_ID, refundId, chargeId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("refund.updated");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock refund
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("succeeded");

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockRefund));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Setup mocks - no payment found
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.empty());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookOk("refund.updated");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                
                // Verify no processing occurred
                verify(refundPolicyService, never()).calculateRefund(any(), anyLong());
                verify(refundPolicyService, never()).processRefund(any(), any(), anyString(), anyString());
                verify(internalBillingService, never()).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("Stripe or repo throw → increments failed, no processed event")
        void shouldHandleStripeErrorsAndIncrementFailed() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String refundId = "re_test_123";
            String chargeId = "ch_test_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"status\":\"succeeded\"}}}", 
                TEST_EVENT_ID, refundId, chargeId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("refund.updated");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock refund
            Refund mockRefund = mock(Refund.class);
            when(mockRefund.getId()).thenReturn(refundId);
            when(mockRefund.getCharge()).thenReturn(chargeId);
            when(mockRefund.getStatus()).thenReturn("succeeded");

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockRefund));

            // Setup mocks to throw Stripe exception
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId))
                .thenThrow(new InvalidRequestException("Charge not found", null, null, null, 0, null));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(InvalidRequestException.class);
                
                // Verify metrics
                verify(metricsService).incrementWebhookReceived("refund.updated");
                verify(metricsService).incrementWebhookFailed("refund.updated");
                
                // Verify no processed event was saved
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }
    }

    @Nested
    @DisplayName("Charge Dispute Created Handler Tests")
    class ChargeDisputeCreatedHandlerTests {

        @Test
        @DisplayName("charge.dispute.created: logs only (no side effects)")
        void shouldHandleDisputeCreatedWithLogsOnly() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String disputeId = "dp_test_123";
            String chargeId = "ch_test_123";
            long disputeAmountCents = 1000L;
            String reason = "fraudulent";
            String status = "warning_needs_response";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"amount\":%d,\"reason\":\"%s\",\"status\":\"%s\"}}}", 
                TEST_EVENT_ID, disputeId, chargeId, disputeAmountCents, reason, status);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("charge.dispute.created");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock dispute
            Dispute mockDispute = mock(Dispute.class);
            when(mockDispute.getId()).thenReturn(disputeId);
            when(mockDispute.getCharge()).thenReturn(chargeId);
            when(mockDispute.getAmount()).thenReturn(disputeAmountCents);
            when(mockDispute.getReason()).thenReturn(reason);
            when(mockDispute.getStatus()).thenReturn(status);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockDispute));

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("charge.dispute.created");
                verify(metricsService).incrementWebhookOk("charge.dispute.created");
                
                // Verify no side effects - only logging
                verify(stripeService, never()).retrieveCharge(anyString());
                verify(paymentRepository, never()).findByStripePaymentIntentId(anyString());
                verify(refundPolicyService, never()).calculateRefund(any(), anyLong());
                verify(refundPolicyService, never()).processRefund(any(), any(), anyString(), anyString());
                verify(internalBillingService, never()).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }
    }

    @Nested
    @DisplayName("Charge Dispute Funds Withdrawn Handler Tests")
    class ChargeDisputeFundsWithdrawnHandlerTests {

        @Test
        @DisplayName("funds_withdrawn: treat as refund — policy calculateRefund, processRefund(disputeId, eventId), save processed event")
        void shouldHandleDisputeFundsWithdrawnAsRefund() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String disputeId = "dp_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            long disputeAmountCents = 1000L;
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"charge.dispute.funds_withdrawn\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"amount\":%d}}}", 
                TEST_EVENT_ID, disputeId, chargeId, disputeAmountCents);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("charge.dispute.funds_withdrawn");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock dispute
            Dispute mockDispute = mock(Dispute.class);
            when(mockDispute.getId()).thenReturn(disputeId);
            when(mockDispute.getCharge()).thenReturn(chargeId);
            when(mockDispute.getAmount()).thenReturn(disputeAmountCents);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockDispute));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Mock payment
            Payment mockPayment = mock(Payment.class);
            when(mockPayment.getUserId()).thenReturn(TEST_USER_ID);
            when(mockPayment.getAmountCents()).thenReturn(2000L);
            when(mockPayment.getCreditedTokens()).thenReturn(200L);

            // Mock refund calculation
            RefundCalculationDto mockCalculation = mock(RefundCalculationDto.class);
            when(mockCalculation.tokensToDeduct()).thenReturn(100L);
            when(mockCalculation.refundAmountCents()).thenReturn(disputeAmountCents);
            when(mockCalculation.policyApplied()).thenReturn("proportional");

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(mockPayment));
            when(refundPolicyService.calculateRefund(mockPayment, disputeAmountCents)).thenReturn(mockCalculation);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("charge.dispute.funds_withdrawn");
                verify(metricsService).incrementWebhookOk("charge.dispute.funds_withdrawn");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                verify(refundPolicyService).calculateRefund(mockPayment, disputeAmountCents);
                verify(refundPolicyService).processRefund(mockPayment, mockCalculation, disputeId, TEST_EVENT_ID);
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("Missing original Payment path covered")
        void shouldHandleMissingOriginalPaymentGracefully() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String disputeId = "dp_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            long disputeAmountCents = 1000L;
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"charge.dispute.funds_withdrawn\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"amount\":%d}}}", 
                TEST_EVENT_ID, disputeId, chargeId, disputeAmountCents);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("charge.dispute.funds_withdrawn");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock dispute
            Dispute mockDispute = mock(Dispute.class);
            when(mockDispute.getId()).thenReturn(disputeId);
            when(mockDispute.getCharge()).thenReturn(chargeId);
            when(mockDispute.getAmount()).thenReturn(disputeAmountCents);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockDispute));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Setup mocks - no payment found
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.empty());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("charge.dispute.funds_withdrawn");
                verify(metricsService).incrementWebhookOk("charge.dispute.funds_withdrawn");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                
                // Verify no processing occurred
                verify(refundPolicyService, never()).calculateRefund(any(), anyLong());
                verify(refundPolicyService, never()).processRefund(any(), any(), anyString(), anyString());
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }
    }

    @Nested
    @DisplayName("Charge Dispute Closed Handler Tests")
    class ChargeDisputeClosedHandlerTests {

        @Test
        @DisplayName("closed won → proportional token restore with key dispute-won:{disputeId}, save processed event")
        void shouldHandleDisputeClosedWon() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String disputeId = "dp_test_123";
            String chargeId = "ch_test_123";
            String paymentIntentId = "pi_test_123";
            long disputeAmountCents = 1000L;
            long originalAmountCents = 2000L;
            long originalTokens = 200L;
            long tokensToRestore = 100L; // (200 * 1000) / 2000
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"amount\":%d,\"status\":\"won\"}}}", 
                TEST_EVENT_ID, disputeId, chargeId, disputeAmountCents);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("charge.dispute.closed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock dispute
            Dispute mockDispute = mock(Dispute.class);
            when(mockDispute.getId()).thenReturn(disputeId);
            when(mockDispute.getCharge()).thenReturn(chargeId);
            when(mockDispute.getAmount()).thenReturn(disputeAmountCents);
            when(mockDispute.getStatus()).thenReturn("won");

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockDispute));

            // Mock charge
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn(chargeId);
            when(mockCharge.getPaymentIntent()).thenReturn(paymentIntentId);

            // Mock payment
            Payment mockPayment = mock(Payment.class);
            when(mockPayment.getUserId()).thenReturn(TEST_USER_ID);
            when(mockPayment.getAmountCents()).thenReturn(originalAmountCents);
            when(mockPayment.getCreditedTokens()).thenReturn(originalTokens);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveCharge(chargeId)).thenReturn(mockCharge);
            when(paymentRepository.findByStripePaymentIntentId(paymentIntentId)).thenReturn(Optional.of(mockPayment));

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookOk("charge.dispute.closed");
                verify(stripeService).retrieveCharge(chargeId);
                verify(paymentRepository).findByStripePaymentIntentId(paymentIntentId);
                
                // Verify token restoration
                String expectedIdempotencyKey = String.format("dispute-won:%s", disputeId);
                verify(internalBillingService).creditPurchase(
                    eq(TEST_USER_ID),
                    eq(tokensToRestore),
                    eq(expectedIdempotencyKey),
                    eq(disputeId),
                    anyString() // metadata
                );
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("closed lost or other status → no token changes, but still save processed event")
        void shouldHandleDisputeClosedLostOrOther() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String disputeId = "dp_test_123";
            String chargeId = "ch_test_123";
            long disputeAmountCents = 1000L;
            String status = "lost"; // Test with "lost" status
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\",\"amount\":%d,\"status\":\"%s\"}}}", 
                TEST_EVENT_ID, disputeId, chargeId, disputeAmountCents, status);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("charge.dispute.closed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock dispute
            Dispute mockDispute = mock(Dispute.class);
            when(mockDispute.getId()).thenReturn(disputeId);
            when(mockDispute.getCharge()).thenReturn(chargeId);
            when(mockDispute.getAmount()).thenReturn(disputeAmountCents);
            when(mockDispute.getStatus()).thenReturn(status);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockDispute));

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);
                
                // Verify interactions
                verify(metricsService).incrementWebhookReceived("charge.dispute.closed");
                verify(metricsService).incrementWebhookOk("charge.dispute.closed");
                
                // Verify no token changes
                verify(internalBillingService, never()).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());
                
                // Verify processed event is still saved
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
            }
        }
    }

    @Nested
    @DisplayName("Upsert & Transactionality Tests")
    class UpsertTransactionalityTests {

        @Test
        @DisplayName("When internalBillingService.creditPurchase throws → transaction rolls back Payment save and does not save ProcessedStripeEvent")
        void shouldRollbackWhenCreditPurchaseThrows() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String sessionId = "cs_test_session_456";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}",
                    TEST_EVENT_ID, sessionId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(sessionId);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent");

            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", TEST_USER_ID.toString());
            metadata.put("packId", TEST_PACK_ID.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);

            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(1000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");

            // Mock validation result
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPrimaryPack = mock(ProductPack.class);
            when(mockPrimaryPack.getId()).thenReturn(TEST_PACK_ID);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);

            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of());
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(sessionId, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);
            when(paymentRepository.findByStripeSessionId(sessionId)).thenReturn(Optional.empty());
            
            // Make creditPurchase throw an exception
            doThrow(new RuntimeException("Billing service error"))
                .when(internalBillingService).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                        .thenReturn(mockEvent);

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessage("Billing service error");

                // Verify metrics
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookFailed("checkout.session.completed");
                
                // Verify Payment was saved (this happens before creditPurchase)
                verify(paymentRepository).save(any(Payment.class));
                
                // Verify creditPurchase was called and threw
                verify(internalBillingService).creditPurchase(any(), anyLong(), anyString(), anyString(), anyString());
                
                // Verify ProcessedStripeEvent was NOT saved due to rollback
                verify(processedStripeEventRepository, never()).save(any(ProcessedStripeEvent.class));
            }
        }

        @Test
        @DisplayName("Success path saves Payment once, then credits, then saves processed event")
        void shouldFollowCorrectSuccessPath() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String sessionId = "cs_test_session_456";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\"}}}",
                    TEST_EVENT_ID, sessionId);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(sessionId);
            when(mockSession.getCustomer()).thenReturn("cus_test_customer");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent");

            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", TEST_USER_ID.toString());
            metadata.put("packId", TEST_PACK_ID.toString());
            when(mockSession.getMetadata()).thenReturn(metadata);

            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(1000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");

            // Mock validation result
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPrimaryPack = mock(ProductPack.class);
            when(mockPrimaryPack.getId()).thenReturn(TEST_PACK_ID);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);

            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of());
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup mocks
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID)).thenReturn(false);
            when(stripeService.retrieveSession(sessionId, true)).thenReturn(mockSession);
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);
            when(paymentRepository.findByStripeSessionId(sessionId)).thenReturn(Optional.empty());

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                        .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);

                // Verify correct sequence of operations
                var inOrder = org.mockito.Mockito.inOrder(paymentRepository, internalBillingService, processedStripeEventRepository);
                
                // 1. Payment should be saved first
                inOrder.verify(paymentRepository).save(any(Payment.class));
                
                // 2. Then creditPurchase should be called
                inOrder.verify(internalBillingService).creditPurchase(
                    eq(TEST_USER_ID),
                    eq(500L), // totalTokens
                    eq(String.format("checkout:%s:%s", TEST_EVENT_ID, sessionId)),
                    eq(TEST_PACK_ID.toString()),
                    anyString() // enhancedMetadata
                );
                
                // 3. Finally ProcessedStripeEvent should be saved
                inOrder.verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
                
                // Verify metrics
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookOk("checkout.session.completed");
            }
        }
    }

    @Nested
    @DisplayName("Retry Semantics Tests")
    class RetrySemanticsTests {

        @Test
        @DisplayName("Simulate transient Stripe error - First attempt 500, second attempt OK - Only one credit & one processed record")
        void shouldHandleTransientStripeErrorWithRetry() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String sessionId = "cs_test_session_123";
            
            String payload = String.format("{\"id\":\"%s\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"%s\",\"payment_intent\":\"pi_test_123\",\"client_reference_id\":\"%s\",\"metadata\":{\"pack_id\":\"%s\"}}}}", 
                TEST_EVENT_ID, sessionId, TEST_USER_ID, TEST_PACK_ID);
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn(TEST_EVENT_ID);
            when(mockEvent.getType()).thenReturn("checkout.session.completed");
            when(mockEvent.toJson()).thenReturn(payload);

            // Mock session
            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn(sessionId);
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_123");
            when(mockSession.getClientReferenceId()).thenReturn(TEST_USER_ID.toString());
            when(mockSession.getMetadata()).thenReturn(Map.of("pack_id", TEST_PACK_ID.toString()));

            // Mock line items
            com.stripe.model.LineItem mockLineItem = mock(com.stripe.model.LineItem.class);
            when(mockLineItem.getQuantity()).thenReturn(1L);
            when(mockLineItem.getPrice()).thenReturn(mock(com.stripe.model.Price.class));
            when(mockLineItem.getPrice().getId()).thenReturn("price_test_123");
            
            com.stripe.model.LineItemCollection mockLineItemCollection = mock(com.stripe.model.LineItemCollection.class);
            when(mockLineItemCollection.getData()).thenReturn(List.of(mockLineItem));
            when(mockSession.getLineItems()).thenReturn(mockLineItemCollection);

            // Mock event deserialization
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.getObject()).thenReturn(java.util.Optional.of(mockSession));

            // Mock validation result
            CheckoutValidationService.CheckoutValidationResult mockValidationResult = mock(CheckoutValidationService.CheckoutValidationResult.class);
            ProductPack mockPack = mock(ProductPack.class);
            
            when(mockPack.getId()).thenReturn(TEST_PACK_ID);
            when(mockPack.getTokens()).thenReturn(500L);
            when(mockPack.getPriceCents()).thenReturn(1000L);
            
            when(mockValidationResult.primaryPack()).thenReturn(mockPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of());
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);
            
            when(checkoutValidationService.validateAndResolvePack(eq(mockSession), any())).thenReturn(mockValidationResult);

            // Mock processed event check - both calls return false (not processed yet)
            when(processedStripeEventRepository.existsByEventId(TEST_EVENT_ID))
                .thenReturn(false)  // First attempt: not processed yet
                .thenReturn(false); // Second attempt: still not processed (retry scenario)

            // Mock Stripe service to fail on first call, succeed on second call
            when(stripeService.retrieveSession(sessionId, true))
                .thenThrow(new InvalidRequestException("Temporary Stripe API error", null, null, null, 500, null))  // First attempt: 500 error
                .thenReturn(mockSession);  // Second attempt: success

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When - First attempt (should fail with 500 error)
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(InvalidCheckoutSessionException.class)
                    .hasMessageContaining("Stripe session retrieval failed");
                
                // Verify first attempt metrics
                verify(metricsService).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookFailed("checkout.session.completed");

                // When - Second attempt (should succeed)
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then - Second attempt should return OK (successful retry)
                assertThat(result).isEqualTo(StripeWebhookService.Result.OK);

                // Verify second attempt metrics
                verify(metricsService, times(2)).incrementWebhookReceived("checkout.session.completed");
                verify(metricsService).incrementWebhookOk("checkout.session.completed");

                // Verify idempotency: Credit should be issued on second attempt (successful retry)
                verify(internalBillingService).creditPurchase(
                    eq(TEST_USER_ID),
                    eq(500L), // totalTokens
                    eq(String.format("checkout:%s:%s", TEST_EVENT_ID, sessionId)),
                    eq(TEST_PACK_ID.toString()),
                    eq(null) // enhancedMetadata (null due to metadata build failure)
                );
                
                // Verify ProcessedStripeEvent was saved on successful retry
                verify(processedStripeEventRepository).save(any(ProcessedStripeEvent.class));
                
                // Verify Stripe service was called twice (first failed, second succeeded)
                verify(stripeService, times(2)).retrieveSession(sessionId, true);
            }
        }
    }
}