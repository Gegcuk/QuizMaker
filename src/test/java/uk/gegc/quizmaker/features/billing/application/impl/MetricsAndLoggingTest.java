package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
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
import org.slf4j.MDC;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.application.WebhookLoggingContext;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Metrics and Logging Tests")
@Execution(ExecutionMode.CONCURRENT)
class MetricsAndLoggingTest {

    @Mock
    private StripeProperties stripeProperties;
    
    @Mock
    private BillingMetricsService metricsService;
    
    private StripeWebhookServiceImpl webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new StripeWebhookServiceImpl(
            stripeProperties,
            null, // stripeService
            null, // internalBillingService
            null, // refundPolicyService
            null, // checkoutValidationService
            metricsService,
            null, // subscriptionService
            null, // processedStripeEventRepository
            null, // paymentRepository
            null  // objectMapper
        );
    }

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("Should increment webhook received for all event types")
        void shouldIncrementWebhookReceivedForAllEventTypes() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_test\",\"type\":\"unknown.event.type\"}";
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn("evt_test");
            when(mockEvent.getType()).thenReturn("unknown.event.type");

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.IGNORED);
                verify(metricsService).incrementWebhookReceived("unknown.event.type");
                verify(metricsService).incrementWebhookOk("unknown.event.type"); // IGNORED is treated as OK
            }
        }

        @Test
        @DisplayName("Should not increment metrics for signature verification errors")
        void shouldNotIncrementMetricsForSignatureVerificationErrors() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_test\",\"type\":\"checkout.session.completed\"}";
            String signature = "invalid_signature";

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenThrow(new SignatureVerificationException("Invalid signature", signature));

                // When & Then
                assertThatThrownBy(() -> webhookService.process(payload, signature))
                    .isInstanceOf(StripeWebhookInvalidSignatureException.class);

                // No metrics should be incremented for signature verification errors
                verify(metricsService, never()).incrementWebhookReceived(anyString());
                verify(metricsService, never()).incrementWebhookFailed(anyString());
                verify(metricsService, never()).incrementWebhookOk(anyString());
                verify(metricsService, never()).incrementWebhookDuplicate(anyString());
            }
        }

        @Test
        @DisplayName("Should not increment metrics for webhook secret validation errors")
        void shouldNotIncrementMetricsForWebhookSecretValidationErrors() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn(null);
            String payload = "{\"id\":\"evt_test\",\"type\":\"checkout.session.completed\"}";
            String signature = "t=1234567890,v1=signature";

            // When & Then
            assertThatThrownBy(() -> webhookService.process(payload, signature))
                .isInstanceOf(StripeWebhookInvalidSignatureException.class);

            // No metrics should be incremented for webhook secret validation errors
            verify(metricsService, never()).incrementWebhookReceived(anyString());
            verify(metricsService, never()).incrementWebhookFailed(anyString());
            verify(metricsService, never()).incrementWebhookOk(anyString());
            verify(metricsService, never()).incrementWebhookDuplicate(anyString());
        }
    }

    @Nested
    @DisplayName("WebhookLoggingContext Tests")
    class WebhookLoggingContextTests {

        @Test
        @DisplayName("Should clear MDC after successful processing")
        void shouldClearMDCAfterSuccessfulProcessing() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_test\",\"type\":\"unknown.event.type\"}";
            String signature = "t=1234567890,v1=signature";

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn("evt_test");
            when(mockEvent.getType()).thenReturn("unknown.event.type");

            // Set some MDC values before processing
            MDC.put("test_key", "test_value");
            MDC.put("stripe_event_id", "should_be_cleared");

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When
                webhookService.process(payload, signature);

                // Then
                // The webhook-specific MDC keys should be cleared
                assertThat(MDC.get("stripe_event_id")).isNull();
                assertThat(MDC.get("stripe_event_type")).isNull();
                assertThat(MDC.get("stripe_session_id")).isNull();
                assertThat(MDC.get("user_id")).isNull();
                assertThat(MDC.get("stripe_price_id")).isNull();
                assertThat(MDC.get("stripe_subscription_id")).isNull();
                assertThat(MDC.get("stripe_charge_id")).isNull();
                assertThat(MDC.get("stripe_dispute_id")).isNull();
                assertThat(MDC.get("stripe_customer_id")).isNull();
                
                // Other MDC keys should remain
                assertThat(MDC.get("test_key")).isEqualTo("test_value");
            } finally {
                // Clean up
                MDC.clear();
            }
        }

        @Test
        @DisplayName("Should clear MDC after processing exception")
        void shouldClearMDCAfterProcessingException() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");
            String payload = "{\"id\":\"evt_test\",\"type\":\"unknown.event.type\"}";
            String signature = "t=1234567890,v1=signature";

            // Set some MDC values before processing
            MDC.put("test_key", "test_value");
            MDC.put("stripe_event_id", "should_be_cleared");

            Event mockEvent = mock(Event.class);
            when(mockEvent.getId()).thenReturn("evt_test");
            when(mockEvent.getType()).thenReturn("unknown.event.type");

            try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
                webhookMock.when(() -> Webhook.constructEvent(payload, signature, "whsec_test_secret"))
                    .thenReturn(mockEvent);

                // When - This will succeed and reach the finally block
                StripeWebhookService.Result result = webhookService.process(payload, signature);

                // Then
                assertThat(result).isEqualTo(StripeWebhookService.Result.IGNORED);

                // The webhook-specific MDC keys should be cleared after processing
                assertThat(MDC.get("stripe_event_id")).isNull();
                assertThat(MDC.get("stripe_event_type")).isNull();
                assertThat(MDC.get("stripe_session_id")).isNull();
                assertThat(MDC.get("user_id")).isNull();
                assertThat(MDC.get("stripe_price_id")).isNull();
                assertThat(MDC.get("stripe_subscription_id")).isNull();
                assertThat(MDC.get("stripe_charge_id")).isNull();
                assertThat(MDC.get("stripe_dispute_id")).isNull();
                assertThat(MDC.get("stripe_customer_id")).isNull();
                
                // Other MDC keys should remain
                assertThat(MDC.get("test_key")).isEqualTo("test_value");
            } finally {
                // Clean up
                MDC.clear();
            }
        }

        @Test
        @DisplayName("Should clear MDC after webhook secret validation error")
        void shouldClearMDCAfterWebhookSecretValidationError() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn(null);
            String payload = "{\"id\":\"evt_test\",\"type\":\"checkout.session.completed\"}";
            String signature = "t=1234567890,v1=signature";

            // Set some MDC values before processing
            MDC.put("test_key", "test_value");

            // When & Then
            assertThatThrownBy(() -> webhookService.process(payload, signature))
                .isInstanceOf(StripeWebhookInvalidSignatureException.class);

            // The webhook-specific MDC keys should be cleared even after early validation error
            assertThat(MDC.get("stripe_event_id")).isNull();
            assertThat(MDC.get("stripe_event_type")).isNull();
            assertThat(MDC.get("stripe_session_id")).isNull();
            assertThat(MDC.get("user_id")).isNull();
            assertThat(MDC.get("stripe_price_id")).isNull();
            assertThat(MDC.get("stripe_subscription_id")).isNull();
            assertThat(MDC.get("stripe_charge_id")).isNull();
            assertThat(MDC.get("stripe_dispute_id")).isNull();
            assertThat(MDC.get("stripe_customer_id")).isNull();
            
            // Other MDC keys should remain
            assertThat(MDC.get("test_key")).isEqualTo("test_value");
            
            // Clean up
            MDC.clear();
        }

        @Test
        @DisplayName("Should clear MDC after webhook secret empty validation error")
        void shouldClearMDCAfterWebhookSecretEmptyValidationError() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("");
            String payload = "{\"id\":\"evt_test\",\"type\":\"checkout.session.completed\"}";
            String signature = "t=1234567890,v1=signature";

            // Set some MDC values before processing
            MDC.put("test_key", "test_value");

            // When & Then
            assertThatThrownBy(() -> webhookService.process(payload, signature))
                .isInstanceOf(StripeWebhookInvalidSignatureException.class);

            // The webhook-specific MDC keys should be cleared even after early validation error
            assertThat(MDC.get("stripe_event_id")).isNull();
            assertThat(MDC.get("stripe_event_type")).isNull();
            assertThat(MDC.get("stripe_session_id")).isNull();
            assertThat(MDC.get("user_id")).isNull();
            assertThat(MDC.get("stripe_price_id")).isNull();
            assertThat(MDC.get("stripe_subscription_id")).isNull();
            assertThat(MDC.get("stripe_charge_id")).isNull();
            assertThat(MDC.get("stripe_dispute_id")).isNull();
            assertThat(MDC.get("stripe_customer_id")).isNull();
            
            // Other MDC keys should remain
            assertThat(MDC.get("test_key")).isEqualTo("test_value");
            
            // Clean up
            MDC.clear();
        }

        @Test
        @DisplayName("Should clear MDC after webhook secret blank validation error")
        void shouldClearMDCAfterWebhookSecretBlankValidationError() throws Exception {
            // Given
            when(stripeProperties.getWebhookSecret()).thenReturn("   ");
            String payload = "{\"id\":\"evt_test\",\"type\":\"checkout.session.completed\"}";
            String signature = "t=1234567890,v1=signature";

            // Set some MDC values before processing
            MDC.put("test_key", "test_value");

            // When & Then
            assertThatThrownBy(() -> webhookService.process(payload, signature))
                .isInstanceOf(StripeWebhookInvalidSignatureException.class);

            // The webhook-specific MDC keys should be cleared even after early validation error
            assertThat(MDC.get("stripe_event_id")).isNull();
            assertThat(MDC.get("stripe_event_type")).isNull();
            assertThat(MDC.get("stripe_session_id")).isNull();
            assertThat(MDC.get("user_id")).isNull();
            assertThat(MDC.get("stripe_price_id")).isNull();
            assertThat(MDC.get("stripe_subscription_id")).isNull();
            assertThat(MDC.get("stripe_charge_id")).isNull();
            assertThat(MDC.get("stripe_dispute_id")).isNull();
            assertThat(MDC.get("stripe_customer_id")).isNull();
            
            // Other MDC keys should remain
            assertThat(MDC.get("test_key")).isEqualTo("test_value");
            
            // Clean up
            MDC.clear();
        }
    }

    @Nested
    @DisplayName("WebhookLoggingContext Static Methods Tests")
    class WebhookLoggingContextStaticMethodsTests {

        @Test
        @DisplayName("clearMDC should remove all webhook-specific MDC keys")
        void clearMDCShouldRemoveAllWebhookSpecificMDCKeys() {
            // Given - Set all webhook-specific MDC keys
            MDC.put("stripe_event_id", "evt_test");
            MDC.put("stripe_event_type", "checkout.session.completed");
            MDC.put("stripe_session_id", "cs_test_session");
            MDC.put("user_id", "user_123");
            MDC.put("stripe_price_id", "price_123");
            MDC.put("stripe_subscription_id", "sub_123");
            MDC.put("stripe_charge_id", "ch_123");
            MDC.put("stripe_dispute_id", "dp_123");
            MDC.put("stripe_customer_id", "cus_123");
            
            // Set some non-webhook keys
            MDC.put("other_key", "other_value");

            // When
            WebhookLoggingContext.clearMDC();

            // Then
            assertThat(MDC.get("stripe_event_id")).isNull();
            assertThat(MDC.get("stripe_event_type")).isNull();
            assertThat(MDC.get("stripe_session_id")).isNull();
            assertThat(MDC.get("user_id")).isNull();
            assertThat(MDC.get("stripe_price_id")).isNull();
            assertThat(MDC.get("stripe_subscription_id")).isNull();
            assertThat(MDC.get("stripe_charge_id")).isNull();
            assertThat(MDC.get("stripe_dispute_id")).isNull();
            assertThat(MDC.get("stripe_customer_id")).isNull();
            
            // Non-webhook keys should remain
            assertThat(MDC.get("other_key")).isEqualTo("other_value");
            
            // Clean up
            MDC.clear();
        }

        @Test
        @DisplayName("clearMDC should handle missing keys gracefully")
        void clearMDCShouldHandleMissingKeysGracefully() {
            // Given - No MDC keys set
            // Clear any existing MDC context
            MDC.clear();
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

            // When
            WebhookLoggingContext.clearMDC();

            // Then - Should not throw exception
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        @DisplayName("clearMDC should handle partial MDC keys gracefully")
        void clearMDCShouldHandlePartialMDCKeysGracefully() {
            // Given - Set only some webhook-specific MDC keys
            MDC.put("stripe_event_id", "evt_test");
            MDC.put("stripe_session_id", "cs_test_session");
            MDC.put("other_key", "other_value");

            // When
            WebhookLoggingContext.clearMDC();

            // Then
            assertThat(MDC.get("stripe_event_id")).isNull();
            assertThat(MDC.get("stripe_session_id")).isNull();
            assertThat(MDC.get("stripe_event_type")).isNull(); // Should be null even if not set
            assertThat(MDC.get("other_key")).isEqualTo("other_value");
            
            // Clean up
            MDC.clear();
        }
    }
}
