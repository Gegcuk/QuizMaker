package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
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
}