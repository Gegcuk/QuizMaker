package uk.gegc.quizmaker.features.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create",
    "quizmaker.features.billing=true"
})
@Import(BillingErrorHandler.class)
class StripeWebhookControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeWebhookService webhookService;

    @MockitoBean
    private FeatureFlags featureFlags;

    @Test
    @DisplayName("Invalid JSON returns 400 via handler")
    void invalidJson_returns400() throws Exception {
        when(featureFlags.isBilling()).thenReturn(true);
        // Simulate service mapping malformed JSON to IllegalArgumentException
        when(webhookService.process(any(), any())).thenThrow(new IllegalArgumentException("Malformed JSON payload"));

        String invalidJson = "{\n  \"id\": \"evt_test_webhook\",\n  \"object\": \"event\""; // missing closing

        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing signature returns 401 via handler")
    void missingSignature_returns401() throws Exception {
        when(featureFlags.isBilling()).thenReturn(true);
        when(webhookService.process(any(), any())).thenThrow(new StripeWebhookInvalidSignatureException("Missing Stripe signature"));

        String validJson = "{\n  \"id\": \"evt_test_webhook\",\n  \"object\": \"event\"\n}";

        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Valid webhook returns 200")
    void validWebhook_returns200() throws Exception {
        when(featureFlags.isBilling()).thenReturn(true);
        when(webhookService.process(any(), any())).thenReturn(StripeWebhookService.Result.OK);

        String validJson = "{\n  \"id\": \"evt_test_webhook\",\n  \"object\": \"event\",\n  \"type\": \"customer.created\"\n}";

        mockMvc.perform(post("/api/v1/billing/stripe/webhook")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .content(validJson))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}

