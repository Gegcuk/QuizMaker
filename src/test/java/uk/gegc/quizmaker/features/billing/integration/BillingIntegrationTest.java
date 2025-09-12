package uk.gegc.quizmaker.features.billing.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simple integration tests for billing feature.
 * Tests basic functionality without complex Spring context loading.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create",
    "stripe.webhook.secret=whsec_test_secret_123456789",
    "stripe.secret-key=sk_test_mock_key_for_testing"
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
}
