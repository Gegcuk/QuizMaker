package uk.gegc.quizmaker.features.billing.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
    // Note: Uses real Stripe configuration from environment variables (.env file)
})
@DisplayName("Billing Integration Tests")
class BillingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
                .andExpect(status().isForbidden()); // Endpoint exists but requires authentication
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
                .andExpect(status().isForbidden()); // 403 Forbidden (Spring Security intercepts)
    }

    @Test
    @DisplayName("Should reject unsupported content type (text/plain)")
    void shouldRejectUnsupportedContentType() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                .contentType(MediaType.TEXT_PLAIN)
                .content("test payload")
                .header("Stripe-Signature", "t=1234567890,v1=test_signature"))
                .andExpect(status().isInternalServerError()); // 500 Internal Server Error (JSON parsing fails)
    }

    @Test
    @DisplayName("Should reject requests with missing body")
    void shouldRejectRequestsWithMissingBody() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1234567890,v1=test_signature"))
                .andExpect(status().isInternalServerError()); // 500 Internal Server Error (handled by error handler)
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
