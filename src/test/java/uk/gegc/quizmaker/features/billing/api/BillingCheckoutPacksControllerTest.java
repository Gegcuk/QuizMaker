package uk.gegc.quizmaker.features.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.api.dto.PackDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingCheckoutController.class)
@WithMockUser
class BillingCheckoutPacksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingService billingService;

    @MockitoBean
    private CheckoutReadService checkoutReadService;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private EstimationService estimationService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private BillingProperties billingProperties;

    @MockitoBean
    private FeatureFlags featureFlags;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @MockitoBean
    private ProductPackRepository productPackRepository;

    @Test
    @DisplayName("GET /api/v1/billing/packs returns packs filtered by currency when billing enabled")
    void getPacks_withCurrencyFilter_returnsFilteredPacks() throws Exception {
        when(featureFlags.isBilling()).thenReturn(true);
        var pack1 = new PackDto(UUID.randomUUID(), "Starter", "desc", 1000L, 400L, "gbp", "price_1");
        var pack2 = new PackDto(UUID.randomUUID(), "Pro", "desc2", 5000L, 2500L, "gbp", "price_2");
        when(checkoutReadService.getAvailablePacks("gbp")).thenReturn(List.of(pack1, pack2));

        mockMvc.perform(get("/api/v1/billing/packs").param("currency", "gbp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].currency").value("gbp"))
                .andExpect(jsonPath("$[1].currency").value("gbp"))
                .andExpect(jsonPath("$[0].name").value("Starter"))
                .andExpect(jsonPath("$[1].name").value("Pro"));

        verify(checkoutReadService).getAvailablePacks("gbp");
    }

    @Test
    @DisplayName("GET /api/v1/billing/packs returns 404 when billing feature disabled")
    void getPacks_whenBillingDisabled_returnsNotFound() throws Exception {
        when(featureFlags.isBilling()).thenReturn(false);

        mockMvc.perform(get("/api/v1/billing/packs").param("currency", "usd"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(checkoutReadService);
    }
}
