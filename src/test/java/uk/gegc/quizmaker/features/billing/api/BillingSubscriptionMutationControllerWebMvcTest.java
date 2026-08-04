package uk.gegc.quizmaker.features.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Subscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.CheckoutPackResolver;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeSubscriptionUnavailableException;
import uk.gegc.quizmaker.features.billing.domain.exception.SubscriptionMutationConflictException;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingCheckoutController.class)
@Import({
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        BillingSubscriptionMutationControllerWebMvcTest.SpringDocTestConfig.class
})
@DisplayName("Billing subscription mutation HTTP contract")
class BillingSubscriptionMutationControllerWebMvcTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String SUBSCRIPTION_ID = "sub_owner";

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BillingService billingService;

    @MockitoBean
    private CheckoutReadService checkoutReadService;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private SubscriptionMutationService subscriptionMutationService;

    @MockitoBean
    private EstimationService estimationService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private CheckoutPackResolver checkoutPackResolver;

    @MockitoBean
    private BillingProperties billingProperties;

    @MockitoBean
    private FeatureFlags featureFlags;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = "BILLING_WRITE")
    @DisplayName("POST update-subscription delegates a valid compatibility request to the ownership service")
    void updateSubscription_withOwnedRequest_delegatesToOwnershipService() throws Exception {
        Subscription updated = new Subscription();
        updated.setId(SUBSCRIPTION_ID);
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        when(stripeService.resolvePriceIdByLookupKey("pro_monthly")).thenReturn("price_pro_monthly");
        when(subscriptionMutationService.updateSubscription(USER_ID, SUBSCRIPTION_ID, "price_pro_monthly"))
                .thenReturn(updated);

        mockMvc.perform(post("/api/v1/billing/update-subscription")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"subscriptionId\":\"sub_owner\",\"newPriceLookupKey\":\"pro_monthly\"}"))
                .andExpect(status().isOk());

        verify(subscriptionMutationService).updateSubscription(USER_ID, SUBSCRIPTION_ID, "price_pro_monthly");
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = "BILLING_WRITE")
    @DisplayName("POST cancel-subscription returns a generic 403 for a foreign subscription without calling Stripe directly")
    void cancelSubscription_withForeignId_returnsGenericForbidden() throws Exception {
        String foreignSubscriptionId = "sub_other_user";
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        doThrow(new ForbiddenException("Subscription mutation is not permitted"))
                .when(subscriptionMutationService).cancelSubscription(USER_ID, foreignSubscriptionId);

        mockMvc.perform(post("/api/v1/billing/cancel-subscription")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"subscriptionId\":\"sub_other_user\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/access-denied"))
                .andExpect(jsonPath("$.detail").value("Subscription mutation is not permitted"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(foreignSubscriptionId))));

        verifyNoInteractions(stripeService);
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = "BILLING_WRITE")
    @DisplayName("POST update-subscription maps a cancelled subscription to a typed 409 response")
    void updateSubscription_withCancelledSubscription_returnsConflict() throws Exception {
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        when(stripeService.resolvePriceIdByLookupKey("pro_monthly")).thenReturn("price_pro_monthly");
        doThrow(new SubscriptionMutationConflictException("Subscription cannot be updated in its current state"))
                .when(subscriptionMutationService).updateSubscription(USER_ID, SUBSCRIPTION_ID, "price_pro_monthly");

        mockMvc.perform(post("/api/v1/billing/update-subscription")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"subscriptionId\":\"sub_owner\",\"newPriceLookupKey\":\"pro_monthly\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/subscription-mutation-conflict"));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = "BILLING_WRITE")
    @DisplayName("POST cancel-subscription maps Stripe unavailability to a retryable 503 response")
    void cancelSubscription_whenStripeIsUnavailable_returnsServiceUnavailable() throws Exception {
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        doThrow(new StripeSubscriptionUnavailableException(new IllegalStateException("Stripe timeout")))
                .when(subscriptionMutationService).cancelSubscription(USER_ID, SUBSCRIPTION_ID);

        mockMvc.perform(post("/api/v1/billing/cancel-subscription")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"subscriptionId\":\"sub_owner\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/stripe-subscription-unavailable"))
                .andExpect(jsonPath("$.detail").value("Subscription service is temporarily unavailable. Please retry."));
    }

    @Test
    @WithMockUser
    @DisplayName("Billing OpenAPI documents compatible subscription IDs, ownership checks, and retry semantics")
    void billingOpenApi_documentsSubscriptionMutationOwnershipContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/billing"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        JsonNode update = specification.at("/paths/~1api~1v1~1billing~1update-subscription/post");
        JsonNode cancel = specification.at("/paths/~1api~1v1~1billing~1cancel-subscription/post");

        assertThat(update.path("description").asText()).contains("untrusted compatibility field").contains("Stripe customer's userId metadata");
        assertThat(update.path("responses").has("403")).isTrue();
        assertThat(update.path("responses").has("409")).isTrue();
        assertThat(update.path("responses").has("503")).isTrue();
        assertThat(cancel.path("description").asText()).contains("Repeating a completed cancellation is idempotent");
        assertThat(cancel.path("responses").has("403")).isTrue();
        assertThat(cancel.path("responses").has("503")).isTrue();
        assertThat(specification.at("/components/schemas/UpdateSubscriptionRequest/required").toString())
                .contains("subscriptionId").contains("newPriceLookupKey");
        assertThat(specification.at("/components/schemas/CancelSubscriptionRequest/required").toString())
                .contains("subscriptionId");
    }

    @Test
    @WithMockUser(authorities = "BILLING_READ")
    @DisplayName("Billing OpenAPI documents the per-valid-question maximum quote without making new fields required")
    void billingOpenApi_documentsTariffQuoteCompatibility() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/billing"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(specification.at("/paths/~1api~1v1~1billing~1estimate~1quiz-generation/post/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/EstimationDto");
        assertThat(specification.at("/components/schemas/EstimationDto/properties/estimatedBillingTokens/description").asText())
                .contains("never charged more");
        assertThat(specification.at("/components/schemas/EstimationDto/properties/tariffVersion/description").asText())
                .contains("pricing rule");
        assertThat(specification.at("/components/schemas/EstimationDto/properties/billingTokensPerValidQuestion/description").asText())
                .contains("valid accepted question");
        assertThat(specification.at("/components/schemas/EstimationDto/properties/quotedQuestionCount/description").asText())
                .contains("maximum quote");
        assertThat(specification.at("/components/schemas/EstimationDto/required").toString())
                .doesNotContain("tariffVersion")
                .doesNotContain("billingTokensPerValidQuestion")
                .doesNotContain("quotedQuestionCount");
    }
}
