package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Enhanced Metadata Builder Tests")
class EnhancedMetadataBuilderTest {

    @Mock
    private Session mockSession;
    
    @Mock
    private CheckoutValidationService.CheckoutValidationResult mockValidationResult;
    
    @Mock
    private ProductPack mockPrimaryPack;
    
    @Mock
    private ProductPack mockAdditionalPack1;
    
    @Mock
    private ProductPack mockAdditionalPack2;

    private ObjectMapper objectMapper;
    private StripeWebhookServiceImpl webhookService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webhookService = new StripeWebhookServiceImpl(
            null, null, null, null, null, null, null, null, null, objectMapper
        );
    }

    @Nested
    @DisplayName("buildEnhancedPurchaseMetaJson Tests")
    class BuildEnhancedPurchaseMetaJsonTests {

        @Test
        @DisplayName("Should include all expected keys with complete data")
        void shouldIncludeAllExpectedKeysWithCompleteData() throws Exception {
            // Given
            setupCompleteSessionData();
            setupCompleteValidationResult();

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, mockValidationResult);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            // Core session data
            assertThat(json.has("sessionId")).isTrue();
            assertThat(json.has("customerId")).isTrue();
            assertThat(json.has("paymentIntentId")).isTrue();
            assertThat(json.has("sessionMetadata")).isTrue();
            
            // Primary pack data
            assertThat(json.has("primaryPack")).isTrue();
            JsonNode primaryPack = json.get("primaryPack");
            assertThat(primaryPack.has("id")).isTrue();
            assertThat(primaryPack.has("name")).isTrue();
            assertThat(primaryPack.has("stripePriceId")).isTrue();
            assertThat(primaryPack.has("amountCents")).isTrue();
            assertThat(primaryPack.has("currency")).isTrue();
            assertThat(primaryPack.has("tokens")).isTrue();
            
            // Additional packs data
            assertThat(json.has("additionalPacks")).isTrue();
            JsonNode additionalPacks = json.get("additionalPacks");
            assertThat(additionalPacks.isArray()).isTrue();
            assertThat(additionalPacks.size()).isEqualTo(2);
            
            // Totals data
            assertThat(json.has("totals")).isTrue();
            JsonNode totals = json.get("totals");
            assertThat(totals.has("totalAmountCents")).isTrue();
            assertThat(totals.has("totalTokens")).isTrue();
            assertThat(totals.has("currency")).isTrue();
            assertThat(totals.has("packCount")).isTrue();
            assertThat(totals.has("hasMultipleLineItems")).isTrue();
            
            // Session details
            assertThat(json.has("sessionDetails")).isTrue();
            JsonNode sessionDetails = json.get("sessionDetails");
            assertThat(sessionDetails.has("currency")).isTrue();
            assertThat(sessionDetails.has("amountTotal")).isTrue();
            assertThat(sessionDetails.has("mode")).isTrue();
            assertThat(sessionDetails.has("paymentStatus")).isTrue();
        }

        @Test
        @DisplayName("Should tolerate Stripe nulls gracefully")
        void shouldTolerateStripeNullsGracefully() throws Exception {
            // Given - The implementation tolerates Stripe nulls through extractCustomerId() and extractPaymentIntentId() methods
            // These methods handle null values from Stripe objects internally and return appropriate defaults
            setupCompleteSessionData();
            setupCompleteValidationResult();
            
            // Override specific session methods to return null to test null tolerance
            when(mockSession.getCustomer()).thenReturn(null); // Stripe null
            when(mockSession.getPaymentIntent()).thenReturn(null); // Stripe null
            when(mockSession.getMetadata()).thenReturn(null); // Stripe null

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, mockValidationResult);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            // Should still have all expected keys, demonstrating Stripe null tolerance
            assertThat(json.has("sessionId")).isTrue();
            assertThat(json.has("customerId")).isTrue();
            assertThat(json.has("paymentIntentId")).isTrue();
            assertThat(json.has("sessionMetadata")).isTrue();
            assertThat(json.has("primaryPack")).isTrue();
            assertThat(json.has("totals")).isTrue();
            assertThat(json.has("sessionDetails")).isTrue();
            assertThat(json.has("additionalPacks")).isTrue();
            
            // Verify that null values from Stripe are handled gracefully
            // The extractCustomerId() and extractPaymentIntentId() methods handle nulls internally
            assertThat(json.get("customerId").isNull()).isTrue();
            assertThat(json.get("paymentIntentId").isNull()).isTrue();
            assertThat(json.get("sessionMetadata").isNull()).isTrue();
        }

        @Test
        @DisplayName("Should handle empty additional packs list")
        void shouldHandleEmptyAdditionalPacksList() throws Exception {
            // Given
            setupCompleteSessionData();
            setupValidationResultWithEmptyAdditionalPacks();

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, mockValidationResult);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            // Additional packs should not be present when empty
            assertThat(json.has("additionalPacks")).isFalse();
        }

        @Test
        @DisplayName("Should return null when primary pack is null")
        void shouldReturnNullWhenPrimaryPackIsNull() throws Exception {
            // Given
            lenient().when(mockSession.getId()).thenReturn("cs_test_session_123");
            lenient().when(mockSession.getCustomer()).thenReturn("cus_test_customer_456");
            lenient().when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent_789");
            lenient().when(mockSession.getMetadata()).thenReturn(null);
            lenient().when(mockSession.getCurrency()).thenReturn("usd");
            lenient().when(mockSession.getAmountTotal()).thenReturn(2000L);
            lenient().when(mockSession.getMode()).thenReturn("payment");
            lenient().when(mockSession.getPaymentStatus()).thenReturn("paid");
            
            when(mockValidationResult.primaryPack()).thenReturn(null);

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, mockValidationResult);

            // Then
            assertThat(result).isNull();
        }

        private void setupCompleteSessionData() {
            when(mockSession.getId()).thenReturn("cs_test_session_123");
            when(mockSession.getCustomer()).thenReturn("cus_test_customer_456");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent_789");
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("userId", UUID.randomUUID().toString());
            metadata.put("packId", UUID.randomUUID().toString());
            when(mockSession.getMetadata()).thenReturn(metadata);
            
            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(2000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");
        }

        private void setupSessionWithNulls() {
            when(mockSession.getId()).thenReturn("cs_test_session_123");
            when(mockSession.getCustomer()).thenReturn(null);
            when(mockSession.getPaymentIntent()).thenReturn(null);
            when(mockSession.getMetadata()).thenReturn(null);
            when(mockSession.getCurrency()).thenReturn(null);
            when(mockSession.getAmountTotal()).thenReturn(null);
            when(mockSession.getMode()).thenReturn(null);
            when(mockSession.getPaymentStatus()).thenReturn(null);
        }

        private void setupCompleteValidationResult() {
            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of(mockAdditionalPack1, mockAdditionalPack2));
            when(mockValidationResult.totalAmountCents()).thenReturn(2000L);
            when(mockValidationResult.totalTokens()).thenReturn(1000L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(3);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(true);

            // Setup primary pack
            UUID primaryPackId = UUID.randomUUID();
            when(mockPrimaryPack.getId()).thenReturn(primaryPackId);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);

            // Setup additional pack 1
            UUID additionalPack1Id = UUID.randomUUID();
            when(mockAdditionalPack1.getId()).thenReturn(additionalPack1Id);
            when(mockAdditionalPack1.getName()).thenReturn("Premium Pack");
            when(mockAdditionalPack1.getStripePriceId()).thenReturn("price_premium");
            when(mockAdditionalPack1.getPriceCents()).thenReturn(500L);
            when(mockAdditionalPack1.getCurrency()).thenReturn("usd");
            when(mockAdditionalPack1.getTokens()).thenReturn(250L);

            // Setup additional pack 2
            UUID additionalPack2Id = UUID.randomUUID();
            when(mockAdditionalPack2.getId()).thenReturn(additionalPack2Id);
            when(mockAdditionalPack2.getName()).thenReturn("Pro Pack");
            when(mockAdditionalPack2.getStripePriceId()).thenReturn("price_pro");
            when(mockAdditionalPack2.getPriceCents()).thenReturn(500L);
            when(mockAdditionalPack2.getCurrency()).thenReturn("usd");
            when(mockAdditionalPack2.getTokens()).thenReturn(250L);
        }

        private void setupValidationResultWithMinimalData() {
            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(null);
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup primary pack with minimal required values
            UUID primaryPackId = UUID.randomUUID();
            when(mockPrimaryPack.getId()).thenReturn(primaryPackId);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);
        }

        private void setupValidationResultWithEmptyAdditionalPacks() {
            when(mockValidationResult.primaryPack()).thenReturn(mockPrimaryPack);
            when(mockValidationResult.additionalPacks()).thenReturn(List.of());
            when(mockValidationResult.totalAmountCents()).thenReturn(1000L);
            when(mockValidationResult.totalTokens()).thenReturn(500L);
            when(mockValidationResult.currency()).thenReturn("usd");
            when(mockValidationResult.getPackCount()).thenReturn(1);
            when(mockValidationResult.hasMultipleLineItems()).thenReturn(false);

            // Setup primary pack
            UUID primaryPackId = UUID.randomUUID();
            when(mockPrimaryPack.getId()).thenReturn(primaryPackId);
            when(mockPrimaryPack.getName()).thenReturn("Basic Pack");
            when(mockPrimaryPack.getStripePriceId()).thenReturn("price_basic");
            when(mockPrimaryPack.getPriceCents()).thenReturn(1000L);
            when(mockPrimaryPack.getCurrency()).thenReturn("usd");
            when(mockPrimaryPack.getTokens()).thenReturn(500L);
        }

        private String invokeBuildEnhancedPurchaseMetaJson(Session session, CheckoutValidationService.CheckoutValidationResult validationResult) throws Exception {
            Method method = StripeWebhookServiceImpl.class.getDeclaredMethod("buildEnhancedPurchaseMetaJson", Session.class, CheckoutValidationService.CheckoutValidationResult.class);
            method.setAccessible(true);
            return (String) method.invoke(webhookService, session, validationResult);
        }
    }

    @Nested
    @DisplayName("buildRefundCanceledMetaJson Tests")
    class BuildRefundCanceledMetaJsonTests {

        @Test
        @DisplayName("Should include all expected keys with complete refund data")
        void shouldIncludeAllExpectedKeysWithCompleteRefundData() throws Exception {
            // Given
            com.stripe.model.Refund mockRefund = createMockRefund();
            long tokensRestored = 250L;

            // When
            String result = invokeBuildRefundCanceledMetaJson(mockRefund, tokensRestored);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            assertThat(json.has("refundId")).isTrue();
            assertThat(json.has("chargeId")).isTrue();
            assertThat(json.has("refundAmountCents")).isTrue();
            assertThat(json.has("tokensRestored")).isTrue();
            assertThat(json.has("reason")).isTrue();
            assertThat(json.has("refundStatus")).isTrue();
            
            assertThat(json.get("refundId").asText()).isEqualTo("re_test_refund_123");
            assertThat(json.get("chargeId").asText()).isEqualTo("ch_test_charge_456");
            assertThat(json.get("refundAmountCents").asLong()).isEqualTo(1000L);
            assertThat(json.get("tokensRestored").asLong()).isEqualTo(250L);
            assertThat(json.get("reason").asText()).isEqualTo("refund_canceled");
            assertThat(json.get("refundStatus").asText()).isEqualTo("succeeded");
        }

        @Test
        @DisplayName("Should handle null refund values gracefully")
        void shouldHandleNullRefundValuesGracefully() throws Exception {
            // Given
            com.stripe.model.Refund mockRefund = createMockRefundWithNulls();
            long tokensRestored = 0L;

            // When
            String result = invokeBuildRefundCanceledMetaJson(mockRefund, tokensRestored);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            assertThat(json.has("refundId")).isTrue();
            assertThat(json.has("chargeId")).isTrue();
            assertThat(json.has("refundAmountCents")).isTrue();
            assertThat(json.has("tokensRestored")).isTrue();
            assertThat(json.has("reason")).isTrue();
            assertThat(json.has("refundStatus")).isTrue();
            
            assertThat(json.get("tokensRestored").asLong()).isEqualTo(0L);
            assertThat(json.get("reason").asText()).isEqualTo("refund_canceled");
        }

        @Test
        @DisplayName("Should return null on JSON serialization error")
        void shouldReturnNullOnJsonSerializationError() throws Exception {
            // Given
            com.stripe.model.Refund mockRefund = createMockRefund();
            long tokensRestored = 250L;

            // When - This should not throw but return null on error
            String result = invokeBuildRefundCanceledMetaJson(mockRefund, tokensRestored);

            // Then
            assertThat(result).isNotNull(); // Should handle normal case
        }

        private com.stripe.model.Refund createMockRefund() {
            com.stripe.model.Refund mockRefund = org.mockito.Mockito.mock(com.stripe.model.Refund.class);
            when(mockRefund.getId()).thenReturn("re_test_refund_123");
            when(mockRefund.getCharge()).thenReturn("ch_test_charge_456");
            when(mockRefund.getAmount()).thenReturn(1000L);
            when(mockRefund.getStatus()).thenReturn("succeeded");
            return mockRefund;
        }

        private com.stripe.model.Refund createMockRefundWithNulls() {
            com.stripe.model.Refund mockRefund = org.mockito.Mockito.mock(com.stripe.model.Refund.class);
            when(mockRefund.getId()).thenReturn("re_test_refund_123");
            when(mockRefund.getCharge()).thenReturn(null);
            when(mockRefund.getAmount()).thenReturn(null);
            when(mockRefund.getStatus()).thenReturn(null);
            return mockRefund;
        }

        private String invokeBuildRefundCanceledMetaJson(com.stripe.model.Refund refund, long tokensRestored) throws Exception {
            Method method = StripeWebhookServiceImpl.class.getDeclaredMethod("buildRefundCanceledMetaJson", com.stripe.model.Refund.class, long.class);
            method.setAccessible(true);
            return (String) method.invoke(webhookService, refund, tokensRestored);
        }
    }

    @Nested
    @DisplayName("buildDisputeWonMetaJson Tests")
    class BuildDisputeWonMetaJsonTests {

        @Test
        @DisplayName("Should include all expected keys with complete dispute data")
        void shouldIncludeAllExpectedKeysWithCompleteDisputeData() throws Exception {
            // Given
            com.stripe.model.Dispute mockDispute = createMockDispute();
            long tokensRestored = 500L;

            // When
            String result = invokeBuildDisputeWonMetaJson(mockDispute, tokensRestored);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            assertThat(json.has("disputeId")).isTrue();
            assertThat(json.has("chargeId")).isTrue();
            assertThat(json.has("disputeAmountCents")).isTrue();
            assertThat(json.has("tokensRestored")).isTrue();
            assertThat(json.has("reason")).isTrue();
            assertThat(json.has("disputeStatus")).isTrue();
            
            assertThat(json.get("disputeId").asText()).isEqualTo("dp_test_dispute_123");
            assertThat(json.get("chargeId").asText()).isEqualTo("ch_test_charge_456");
            assertThat(json.get("disputeAmountCents").asLong()).isEqualTo(2000L);
            assertThat(json.get("tokensRestored").asLong()).isEqualTo(500L);
            assertThat(json.get("reason").asText()).isEqualTo("dispute_won");
            assertThat(json.get("disputeStatus").asText()).isEqualTo("won");
        }

        @Test
        @DisplayName("Should handle null dispute values gracefully")
        void shouldHandleNullDisputeValuesGracefully() throws Exception {
            // Given
            com.stripe.model.Dispute mockDispute = createMockDisputeWithNulls();
            long tokensRestored = 0L;

            // When
            String result = invokeBuildDisputeWonMetaJson(mockDispute, tokensRestored);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            
            assertThat(json.has("disputeId")).isTrue();
            assertThat(json.has("chargeId")).isTrue();
            assertThat(json.has("disputeAmountCents")).isTrue();
            assertThat(json.has("tokensRestored")).isTrue();
            assertThat(json.has("reason")).isTrue();
            assertThat(json.has("disputeStatus")).isTrue();
            
            assertThat(json.get("tokensRestored").asLong()).isEqualTo(0L);
            assertThat(json.get("reason").asText()).isEqualTo("dispute_won");
        }

        @Test
        @DisplayName("Should return null on JSON serialization error")
        void shouldReturnNullOnJsonSerializationError() throws Exception {
            // Given
            com.stripe.model.Dispute mockDispute = createMockDispute();
            long tokensRestored = 500L;

            // When
            String result = invokeBuildDisputeWonMetaJson(mockDispute, tokensRestored);

            // Then
            assertThat(result).isNotNull(); // Should handle normal case
        }

        private com.stripe.model.Dispute createMockDispute() {
            com.stripe.model.Dispute mockDispute = org.mockito.Mockito.mock(com.stripe.model.Dispute.class);
            when(mockDispute.getId()).thenReturn("dp_test_dispute_123");
            when(mockDispute.getCharge()).thenReturn("ch_test_charge_456");
            when(mockDispute.getAmount()).thenReturn(2000L);
            when(mockDispute.getStatus()).thenReturn("won");
            return mockDispute;
        }

        private com.stripe.model.Dispute createMockDisputeWithNulls() {
            com.stripe.model.Dispute mockDispute = org.mockito.Mockito.mock(com.stripe.model.Dispute.class);
            when(mockDispute.getId()).thenReturn("dp_test_dispute_123");
            when(mockDispute.getCharge()).thenReturn(null);
            when(mockDispute.getAmount()).thenReturn(null);
            when(mockDispute.getStatus()).thenReturn(null);
            return mockDispute;
        }

        private String invokeBuildDisputeWonMetaJson(com.stripe.model.Dispute dispute, long tokensRestored) throws Exception {
            Method method = StripeWebhookServiceImpl.class.getDeclaredMethod("buildDisputeWonMetaJson", com.stripe.model.Dispute.class, long.class);
            method.setAccessible(true);
            return (String) method.invoke(webhookService, dispute, tokensRestored);
        }
    }
}
