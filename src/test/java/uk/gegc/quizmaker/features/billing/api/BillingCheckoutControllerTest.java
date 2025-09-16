package uk.gegc.quizmaker.features.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CreateCheckoutSessionRequest;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.impl.BillingServiceImpl;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for BillingCheckoutController focusing on Day 8 - Top-Up Flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BillingCheckoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BillingServiceImpl billingServiceImpl;

    @MockitoBean
    private CheckoutReadService checkoutReadService;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private EstimationService estimationService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_WRITE"})
    void createCheckoutSession_ShouldReturnSessionUrlAndId() throws Exception {
        // Given
        String priceId = "price_1234567890";
        UUID packId = UUID.randomUUID();
        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest(priceId, packId);
        
        String sessionUrl = "https://checkout.stripe.com/c/pay/cs_test_123";
        String sessionId = "cs_test_123";
        CheckoutSessionResponse expectedResponse = new CheckoutSessionResponse(sessionUrl, sessionId);
        
        // Mock permission check to allow access
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        when(stripeService.createCheckoutSession(any(UUID.class), eq(priceId), eq(packId)))
                .thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/billing/checkout-sessions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.url").value(sessionUrl))
                .andExpect(jsonPath("$.sessionId").value(sessionId));
    }

    @Test
    @WithMockUser(authorities = {"BILLING_WRITE"})
    void createCheckoutSession_WithMissingPriceId_ShouldReturnBadRequest() throws Exception {
        // Given
        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest("", null);
        
        // Mock permission check to allow access
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/v1/billing/checkout-sessions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_READ"}) // Insufficient permissions
    void createCheckoutSession_WithInsufficientPermissions_ShouldReturnForbidden() throws Exception {
        // Given
        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest("price_1234567890", null);
        
        // Mock permission check to deny access
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/v1/billing/checkout-sessions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createCheckoutSession_WithoutAuthentication_ShouldReturnForbidden() throws Exception {
        // Given
        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest("price_123", null);

        // When & Then
        mockMvc.perform(post("/api/v1/billing/checkout-sessions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
