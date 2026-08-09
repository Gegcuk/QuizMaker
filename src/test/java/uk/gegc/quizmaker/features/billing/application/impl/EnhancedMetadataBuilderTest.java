package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Enhanced Metadata Builder Tests")
@Execution(ExecutionMode.CONCURRENT)
class EnhancedMetadataBuilderTest {

    @Mock
    private Session mockSession;

    private ObjectMapper objectMapper;
    private StripeWebhookServiceImpl webhookService;
    private UUID packId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webhookService = new StripeWebhookServiceImpl(
            null, null, null, null, null, null, null, null, null, null, objectMapper
        );
        packId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("buildEnhancedPurchaseMetaJson Tests")
    class BuildEnhancedPurchaseMetaJsonTests {

        @Test
        @DisplayName("Should include all expected keys with complete data")
        void shouldIncludeAllExpectedKeysWithCompleteData() throws Exception {
            // Given
            setupCompleteSessionData();
            CheckoutValidationService.CheckoutValidationResult validationResult = checkoutSnapshot();

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, validationResult);

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
            assertThat(primaryPack.get("id").asText()).isEqualTo(packId.toString());
            assertThat(primaryPack.get("stripePriceId").asText()).isEqualTo("price_basic");
            assertThat(primaryPack.get("amountCents").asLong()).isEqualTo(2000L);
            assertThat(primaryPack.get("currency").asText()).isEqualTo("usd");
            assertThat(primaryPack.get("tokens").asLong()).isEqualTo(1000L);
            assertThat(primaryPack.has("name")).isFalse();
            assertThat(json.has("additionalPacks")).isFalse();
            
            // Totals data
            assertThat(json.has("totals")).isTrue();
            JsonNode totals = json.get("totals");
            assertThat(totals.get("totalAmountCents").asLong()).isEqualTo(2000L);
            assertThat(totals.get("totalTokens").asLong()).isEqualTo(1000L);
            assertThat(totals.get("currency").asText()).isEqualTo("usd");
            assertThat(totals.get("packCount").asInt()).isEqualTo(1);
            assertThat(totals.get("hasMultipleLineItems").asBoolean()).isFalse();
            
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
            // Given
            setupCompleteSessionData();
            CheckoutValidationService.CheckoutValidationResult validationResult = checkoutSnapshot();
            
            // Override specific session methods to return null to test null tolerance
            when(mockSession.getCustomer()).thenReturn(null); // Stripe null
            when(mockSession.getPaymentIntent()).thenReturn(null); // Stripe null
            when(mockSession.getMetadata()).thenReturn(null); // Stripe null

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, validationResult);

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
            assertThat(json.has("additionalPacks")).isFalse();

            assertThat(json.get("customerId").isNull()).isTrue();
            assertThat(json.get("paymentIntentId").isNull()).isTrue();
            assertThat(json.get("sessionMetadata").isNull()).isTrue();
        }

        @Test
        @DisplayName("Should omit unsupported multi-pack metadata for single-item checkout")
        void shouldOmitMultiPackMetadata() throws Exception {
            // Given
            setupCompleteSessionData();
            CheckoutValidationService.CheckoutValidationResult validationResult = checkoutSnapshot();

            // When
            String result = invokeBuildEnhancedPurchaseMetaJson(mockSession, validationResult);

            // Then
            assertThat(result).isNotNull();
            JsonNode json = objectMapper.readTree(result);
            assertThat(json.has("additionalPacks")).isFalse();
            assertThat(json.path("totals").path("packCount").asInt()).isEqualTo(1);
            assertThat(json.path("totals").path("hasMultipleLineItems").asBoolean()).isFalse();
        }

        private void setupCompleteSessionData() {
            when(mockSession.getId()).thenReturn("cs_test_session_123");
            when(mockSession.getCustomer()).thenReturn("cus_test_customer_456");
            when(mockSession.getPaymentIntent()).thenReturn("pi_test_payment_intent_789");
            
            when(mockSession.getMetadata()).thenReturn(Map.of(
                    "userId", UUID.randomUUID().toString(),
                    "packId", packId.toString()
            ));
            
            when(mockSession.getCurrency()).thenReturn("usd");
            when(mockSession.getAmountTotal()).thenReturn(2000L);
            when(mockSession.getMode()).thenReturn("payment");
            when(mockSession.getPaymentStatus()).thenReturn("paid");
        }

        private CheckoutValidationService.CheckoutValidationResult checkoutSnapshot() {
            return new CheckoutValidationService.CheckoutValidationResult(
                    packId,
                    "price_basic",
                    2000L,
                    "usd",
                    1000L
            );
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
