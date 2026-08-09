package uk.gegc.quizmaker.features.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CreateCheckoutSessionRequest;
import uk.gegc.quizmaker.features.billing.api.dto.CreateCustomerRequest;
import uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.impl.BillingServiceImpl;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.CheckoutPackResolver;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.domain.exception.CheckoutPackMismatchException;
import com.stripe.model.Customer;
import java.util.Map;
import java.util.Optional;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for BillingCheckoutController focusing on Day 8 - Top-Up Flow.
 */
@Tag("db-serial")
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "quizmaker.features.billing=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.hibernate.hbm2ddl.auto=update",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
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

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private CheckoutPackResolver checkoutPackResolver;

    @MockitoBean
    private BillingProperties billingProperties;

    @BeforeEach
    void setUp() {
        // Reset mock state before each test to ensure clean test isolation
        // This prevents test interference and ensures predictable test behavior
    }

    @AfterEach
    void tearDown() {
        // Clean up any test-specific state after each test
        // This ensures no side effects between tests
    }

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
        
        ProductPack pack = new ProductPack();
        pack.setId(packId);
        pack.setStripePriceId(priceId);
        pack.setActive(true);
        when(checkoutPackResolver.resolve(packId, priceId)).thenReturn(pack);
        when(stripeService.createCheckoutSession(any(UUID.class), eq(pack)))
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
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_WRITE"})
    @org.junit.jupiter.api.DisplayName("checkout conflict returns RFC 7807 and does not call Stripe or persist payment")
    void createCheckoutSession_WithConflictingPackAndPrice_ReturnsConflictBeforeStripeOrPaymentMutation() throws Exception {
        UUID packId = UUID.randomUUID();
        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest("price_cheaper", packId);
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        doThrow(new CheckoutPackMismatchException("packId and priceId identify different token packs"))
                .when(checkoutPackResolver).resolve(packId, "price_cheaper");

        mockMvc.perform(post("/api/v1/billing/checkout-sessions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/checkout-pack-mismatch"));

        verify(stripeService, never()).createCheckoutSession(any(), any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @WithMockUser
    @org.junit.jupiter.api.DisplayName("billing OpenAPI describes pack-first checkout compatibility and failure modes")
    void checkoutSessionOpenApiContract_DescribesPackFirstCompatibilityAndFailureModes() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/billing"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(specification.at("/paths/~1api~1v1~1billing~1checkout-sessions/post/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/CreateCheckoutSessionRequest");
        assertThat(specification.at("/paths/~1api~1v1~1billing~1checkout-sessions/post/description").asText())
                .contains("snapshotted at creation", "original terms", "deprecated priceId");
        assertThat(specification.at("/components/schemas/CreateCheckoutSessionRequest/properties/priceId/deprecated").asBoolean()).isTrue();
        assertThat(specification.at("/components/schemas/CreateCheckoutSessionRequest/properties/packId/description").asText())
                .contains("server-owned");
        assertThat(specification.at("/paths/~1api~1v1~1billing~1checkout-sessions/post/responses/409").isMissingNode()).isFalse();
        assertThat(specification.at("/paths/~1api~1v1~1billing~1checkout-sessions/post/responses/503").isMissingNode()).isFalse();
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
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_READ"})
    void getCustomer_WithValidMetadataUserId_ShouldReturnCustomer() throws Exception {
        // Given
        String customerId = "cus_test_123";
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock Stripe customer with valid metadata userId
        Customer rawCustomer = new Customer();
        rawCustomer.setId(customerId);
        rawCustomer.setMetadata(Map.of("userId", userId.toString()));
        when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(rawCustomer);
        
        // Mock customer response
        uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse customerResponse = 
                new uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse(customerId, "test@example.com", null);
        when(stripeService.retrieveCustomer(customerId)).thenReturn(customerResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/billing/customers/{customerId}", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_READ"})
    void getCustomer_WithEmailFallbackEnabled_ShouldAllowEmailFallback() throws Exception {
        // Given
        String customerId = "cus_test_123";
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String userEmail = "test@example.com";
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock billing properties to allow email fallback
        when(billingProperties.isAllowEmailFallbackForCustomerOwnership()).thenReturn(true);
        
        // Mock Stripe customer without metadata userId but with matching email
        Customer rawCustomer = new Customer();
        rawCustomer.setId(customerId);
        rawCustomer.setEmail(userEmail);
        rawCustomer.setMetadata(Map.of()); // No userId metadata
        when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(rawCustomer);
        
        // Mock user with matching email
        User user = new User();
        user.setId(userId);
        user.setEmail(userEmail);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        
        // Mock customer response
        uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse customerResponse = 
                new uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse(customerId, userEmail, null);
        when(stripeService.retrieveCustomer(customerId)).thenReturn(customerResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/billing/customers/{customerId}", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.email").value(userEmail));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_READ"})
    void getCustomer_WithEmailFallbackDisabled_ShouldRejectWithoutMetadata() throws Exception {
        // Given
        String customerId = "cus_test_123";
        String userEmail = "test@example.com";
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock billing properties to disable email fallback
        when(billingProperties.isAllowEmailFallbackForCustomerOwnership()).thenReturn(false);
        
        // Mock Stripe customer without metadata userId
        Customer rawCustomer = new Customer();
        rawCustomer.setId(customerId);
        rawCustomer.setEmail(userEmail);
        rawCustomer.setMetadata(Map.of()); // No userId metadata
        when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(rawCustomer);

        // When & Then
        mockMvc.perform(get("/api/v1/billing/customers/{customerId}", customerId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_READ"})
    void getCustomer_WithWrongMetadataUserId_ShouldReject() throws Exception {
        // Given
        String customerId = "cus_test_123";
        UUID wrongUserId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock billing properties to disable email fallback
        when(billingProperties.isAllowEmailFallbackForCustomerOwnership()).thenReturn(false);
        
        // Mock Stripe customer with wrong metadata userId
        Customer rawCustomer = new Customer();
        rawCustomer.setId(customerId);
        rawCustomer.setMetadata(Map.of("userId", wrongUserId.toString()));
        when(stripeService.retrieveCustomerRaw(customerId)).thenReturn(rawCustomer);

        // When & Then
        mockMvc.perform(get("/api/v1/billing/customers/{customerId}", customerId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_WRITE"})
    void createCustomer_ShouldReturnCustomerResponse() throws Exception {
        // Given
        String email = "test@example.com";
        CreateCustomerRequest request = new CreateCustomerRequest(email);
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock rate limiting to allow the request
        doNothing().when(rateLimitService).checkRateLimit(eq("billing-create-customer"), eq(userId.toString()), eq(3));
        
        // Mock Stripe service response
        CustomerResponse expectedResponse = new CustomerResponse("cus_test_123", email, null);
        when(stripeService.createCustomer(eq(userId), eq(email))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/billing/create-customer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("cus_test_123"))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_WRITE"})
    void createCustomer_WhenRateLimitExceeded_ShouldReturnTooManyRequests() throws Exception {
        // Given
        String email = "test@example.com";
        CreateCustomerRequest request = new CreateCustomerRequest(email);
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock rate limiting to throw exception (rate limit exceeded)
        doThrow(new uk.gegc.quizmaker.shared.exception.RateLimitExceededException("Too many requests", 60))
                .when(rateLimitService).checkRateLimit(eq("billing-create-customer"), eq(userId.toString()), eq(3));

        // When & Then
        mockMvc.perform(post("/api/v1/billing/create-customer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000", authorities = {"BILLING_WRITE"})
    void createCustomer_WithMissingEmail_ShouldReturnBadRequest() throws Exception {
        // Given
        CreateCustomerRequest request = new CreateCustomerRequest("");
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        
        // Mock permission check
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        
        // Mock rate limiting to allow the request
        doNothing().when(rateLimitService).checkRateLimit(eq("billing-create-customer"), eq(userId.toString()), eq(3));

        // When & Then
        mockMvc.perform(post("/api/v1/billing/create-customer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = {"BILLING_READ"}) // Insufficient permissions
    void createCustomer_WithInsufficientPermissions_ShouldReturnForbidden() throws Exception {
        // Given
        CreateCustomerRequest request = new CreateCustomerRequest("test@example.com");
        
        // Mock permission check to deny access
        when(appPermissionEvaluator.hasAnyPermission(any())).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/v1/billing/create-customer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createCustomer_WithoutAuthentication_ShouldReturnForbidden() throws Exception {
        // Given
        CreateCustomerRequest request = new CreateCustomerRequest("test@example.com");

        // When & Then
        mockMvc.perform(post("/api/v1/billing/create-customer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }
}
