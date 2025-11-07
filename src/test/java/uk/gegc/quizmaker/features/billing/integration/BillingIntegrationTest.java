package uk.gegc.quizmaker.features.billing.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simple integration tests for billing feature.
 * Tests basic functionality without complex Spring context loading.
 */
@DisplayName("Billing Integration Tests")
class BillingIntegrationTest extends BaseIntegrationTest {


    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Test
    @DisplayName("Should have billing repositories available")
    void shouldHaveBillingRepositoriesAvailable() {
        // Given & When & Then
        assertThat(paymentRepository).isNotNull();
        assertThat(processedStripeEventRepository).isNotNull();
    }

    @Test
    @DisplayName("Should be able to access webhook endpoint")
    void shouldBeAbleToAccessWebhookEndpoint() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/billing/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("Should have empty repositories initially")
    void shouldHaveEmptyRepositoriesInitially() {
        // Given & When & Then
        assertThat(paymentRepository.count()).isEqualTo(0);
        assertThat(processedStripeEventRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reject GET requests to webhook endpoint")
    void shouldRejectGetRequestsToWebhookEndpoint() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/billing/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("Should reject unsupported content type (text/plain)")
    void shouldRejectUnsupportedContentType() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                .contentType(MediaType.TEXT_PLAIN)
                .content("test payload")
                .header("Stripe-Signature", "t=1234567890,v1=test_signature"))
                .andExpect(status().isBadRequest()); // 400 due to malformed JSON
    }

    @Test
    @DisplayName("Should reject requests with missing body")
    void shouldRejectRequestsWithMissingBody() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1234567890,v1=test_signature"))
                .andExpect(status().isBadRequest()); // 400 due to malformed/empty JSON
    }

    @Test
    @DisplayName("Should reject requests with duplicate Stripe-Signature headers")
    void shouldRejectRequestsWithDuplicateStripeSignatureHeaders() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"test\": \"payload\"}")
                .header("Stripe-Signature", "t=1234567890,v1=test_signature_1")
                .header("Stripe-Signature", "t=1234567890,v1=test_signature_2"))
                .andExpect(status().isUnauthorized()); // 401 Unauthorized (webhook secret validation first)
    }
}
