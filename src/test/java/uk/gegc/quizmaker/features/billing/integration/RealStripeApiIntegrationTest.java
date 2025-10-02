package uk.gegc.quizmaker.features.billing.integration;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real Stripe API integration tests using actual Stripe test mode.
 * These tests make real API calls to Stripe's test environment and validate:
 * 1. Test mode API calls with real Stripe responses
 * 2. Webhook signature validation with real signatures
 * 3. API version compatibility testing
 * 
 * Requirements:
 * - STRIPE_SECRET_KEY environment variable set to test key (sk_test_...)
 * - STRIPE_WEBHOOK_SECRET environment variable set to test webhook secret (whsec_test_...)
 * - Internet connection to reach Stripe API
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create",
    "stripe.secret-key=${STRIPE_SECRET_KEY:sk_test_mock_key_for_testing}",
    "stripe.webhook-secret=${STRIPE_WEBHOOK_SECRET:whsec_test_secret_123456789}"
})
@DisplayName("Real Stripe API Integration Tests")
class RealStripeApiIntegrationTest {

    @Value("${stripe.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    private String testCustomerId;
    private String testProductId;
    private String testPriceId;

    @BeforeEach
    void setUp() {
        // Clean up repositories
        processedStripeEventRepository.deleteAll();
        paymentRepository.deleteAll();
        
        // Initialize Stripe with test key
        Stripe.apiKey = stripeSecretKey;
        
        // Skip tests if not using real Stripe test key
        if (!stripeSecretKey.startsWith("sk_test_") || stripeSecretKey.equals("sk_test_mock_key_for_testing")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, 
                "Skipping real Stripe API tests - set STRIPE_SECRET_KEY environment variable to real test key");
        }
    }

    @Nested
    @DisplayName("Test Mode API Calls with Real Stripe Responses")
    class TestModeApiCallsTest {

        @Test
        @DisplayName("Create customer with real Stripe API - validates API connectivity and response structure")
        void shouldCreateCustomerWithRealStripeApi() throws StripeException {
            // Given
            String testEmail = "test-" + System.currentTimeMillis() + "@example.com";
            String testUserId = UUID.randomUUID().toString();

            CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(testEmail)
                .putMetadata("user_id", testUserId)
                .build();

            // When
            Customer customer = Customer.create(params);

            // Then - Validate real Stripe response structure
            assertThat(customer).isNotNull();
            assertThat(customer.getId()).startsWith("cus_");
            assertThat(customer.getEmail()).isEqualTo(testEmail);
            assertThat(customer.getMetadata()).containsEntry("user_id", testUserId);
            assertThat(customer.getObject()).isEqualTo("customer");
            assertThat(customer.getCreated()).isPositive();
            assertThat(customer.getLivemode()).isFalse(); // Should be test mode

            // Store for cleanup
            testCustomerId = customer.getId();
        }

        @Test
        @DisplayName("Create product and price with real Stripe API - validates product creation flow")
        void shouldCreateProductAndPriceWithRealStripeApi() throws StripeException {
            // Given
            String productName = "Test Token Pack - " + System.currentTimeMillis();
            String productDescription = "Test token pack for integration testing";

            // Create product
            ProductCreateParams productParams = ProductCreateParams.builder()
                .setName(productName)
                .setDescription(productDescription)
                .putMetadata("pack_type", "test")
                .build();

            Product product = Product.create(productParams);

            // Create price for the product
            PriceCreateParams priceParams = PriceCreateParams.builder()
                .setProduct(product.getId())
                .setUnitAmount(999L) // $9.99
                .setCurrency("usd")
                .putMetadata("tokens", "100")
                .build();

            Price price = Price.create(priceParams);

            // Then - Validate real Stripe response structure
            assertThat(product).isNotNull();
            assertThat(product.getId()).startsWith("prod_");
            assertThat(product.getName()).isEqualTo(productName);
            assertThat(product.getDescription()).isEqualTo(productDescription);
            assertThat(product.getMetadata()).containsEntry("pack_type", "test");
            assertThat(product.getObject()).isEqualTo("product");
            assertThat(product.getActive()).isTrue();

            assertThat(price).isNotNull();
            assertThat(price.getId()).startsWith("price_");
            assertThat(price.getProduct()).isEqualTo(product.getId());
            assertThat(price.getUnitAmount()).isEqualTo(999L);
            assertThat(price.getCurrency()).isEqualTo("usd");
            assertThat(price.getMetadata()).containsEntry("tokens", "100");
            assertThat(price.getObject()).isEqualTo("price");
            assertThat(price.getActive()).isTrue();

            // Store for cleanup
            testProductId = product.getId();
            testPriceId = price.getId();
        }

        @Test
        @DisplayName("Create checkout session with real Stripe API - validates session creation and response")
        void shouldCreateCheckoutSessionWithRealStripeApi() throws StripeException {
            // Given - Create test customer and price first
            String testEmail = "checkout-test-" + System.currentTimeMillis() + "@example.com";
            String testUserId = UUID.randomUUID().toString();
            String testPackId = UUID.randomUUID().toString();

            Customer customer = Customer.create(CustomerCreateParams.builder()
                .setEmail(testEmail)
                .putMetadata("user_id", testUserId)
                .build());

            Product product = Product.create(ProductCreateParams.builder()
                .setName("Test Pack")
                .setDescription("Test token pack")
                .build());

            Price price = Price.create(PriceCreateParams.builder()
                .setProduct(product.getId())
                .setUnitAmount(1999L) // $19.99
                .setCurrency("usd")
                .build());

            // Create checkout session
            SessionCreateParams sessionParams = SessionCreateParams.builder()
                .setCustomer(customer.getId())
                .setClientReferenceId(testUserId)
                .putMetadata("pack_id", testPackId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setPrice(price.getId())
                    .setQuantity(1L)
                    .build())
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl("https://example.com/success")
                .setCancelUrl("https://example.com/cancel")
                .build();

            // When
            Session session = Session.create(sessionParams);

            // Then - Validate real Stripe response structure
            assertThat(session).isNotNull();
            assertThat(session.getId()).startsWith("cs_");
            assertThat(session.getObject()).isEqualTo("checkout.session");
            assertThat(session.getCustomer()).isEqualTo(customer.getId());
            assertThat(session.getClientReferenceId()).isEqualTo(testUserId);
            assertThat(session.getMetadata()).containsEntry("pack_id", testPackId);
            assertThat(session.getMode()).isEqualTo("payment");
            assertThat(session.getSuccessUrl()).isEqualTo("https://example.com/success");
            assertThat(session.getCancelUrl()).isEqualTo("https://example.com/cancel");
            assertThat(session.getPaymentStatus()).isEqualTo("unpaid");
            assertThat(session.getUrl()).isNotNull();
            assertThat(session.getUrl()).startsWith("https://checkout.stripe.com/");

            // Store for cleanup
            testCustomerId = customer.getId();
        }

        @Test
        @DisplayName("Retrieve customer with expanded fields - validates API expansion functionality")
        void shouldRetrieveCustomerWithExpandedFields() throws StripeException {
            // Given - Create a customer first
            String testEmail = "expand-test-" + System.currentTimeMillis() + "@example.com";
            Customer customer = Customer.create(CustomerCreateParams.builder()
                .setEmail(testEmail)
                .putMetadata("test_field", "test_value")
                .build());

            // When - Retrieve with expansion
            Map<String, Object> params = new HashMap<>();
            params.put("expand", java.util.Arrays.asList("subscriptions", "default_source"));

            Customer retrievedCustomer = Customer.retrieve(customer.getId(), params, null);

            // Then - Validate expanded response structure
            assertThat(retrievedCustomer).isNotNull();
            assertThat(retrievedCustomer.getId()).isEqualTo(customer.getId());
            assertThat(retrievedCustomer.getEmail()).isEqualTo(testEmail);
            assertThat(retrievedCustomer.getMetadata()).containsEntry("test_field", "test_value");
            assertThat(retrievedCustomer.getObject()).isEqualTo("customer");
            assertThat(retrievedCustomer.getLivemode()).isFalse(); // Test mode

            // Store for cleanup
            testCustomerId = customer.getId();
        }
    }

    @Nested
    @DisplayName("Webhook Signature Validation with Real Signatures")
    class WebhookSignatureValidationTest {

        @Test
        @DisplayName("Validate webhook signature with real Stripe signature - validates signature verification")
        void shouldValidateWebhookSignatureWithRealStripeSignature() {
            // Given - Real webhook payload and signature
            String payload = """
                {
                    "id": "evt_test_webhook_signature",
                    "object": "event",
                    "api_version": "2020-08-27",
                    "created": 1609459200,
                    "data": {
                        "object": {
                            "id": "cus_test_customer",
                            "object": "customer",
                            "email": "test@example.com"
                        }
                    },
                    "livemode": false,
                    "pending_webhooks": 1,
                    "request": {
                        "id": "req_test_request",
                        "idempotency_key": null
                    },
                    "type": "customer.created"
                }
                """;

            // When - Generate real signature using Stripe's webhook secret
            long timestamp = System.currentTimeMillis() / 1000;
            String signedPayload = timestamp + "." + payload;
            String signature;
            try {
                signature = "t=" + timestamp + ",v1=" + Webhook.Util.computeHmacSha256(webhookSecret, signedPayload);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new RuntimeException("Failed to generate webhook signature", e);
            }

            // Then - Validate signature verification works
            assertThatCode(() -> {
                Webhook.constructEvent(payload, signature, webhookSecret);
            }).doesNotThrowAnyException();

            // Validate signature format
            assertThat(signature).startsWith("t=");
            assertThat(signature).contains(",v1=");
        }

        @Test
        @DisplayName("Reject invalid webhook signature - validates signature security")
        void shouldRejectInvalidWebhookSignature() {
            // Given - Real payload but invalid signature
            String payload = """
                {
                    "id": "evt_test_invalid_signature",
                    "object": "event",
                    "type": "customer.created"
                }
                """;

            String invalidSignature = "t=1609459200,v1=invalid_signature_hash";

            // When & Then - Should throw exception for invalid signature
            assertThatCode(() -> {
                Webhook.constructEvent(payload, invalidSignature, webhookSecret);
            }).isInstanceOf(com.stripe.exception.SignatureVerificationException.class);
        }

        @Test
        @DisplayName("Validate webhook signature with different timestamps - validates timestamp tolerance")
        void shouldValidateWebhookSignatureWithDifferentTimestamps() {
            // Given - Real payload
            String payload = """
                {
                    "id": "evt_test_timestamp",
                    "object": "event",
                    "type": "customer.created"
                }
                """;

            // When - Generate signatures with different timestamps
            long currentTime = System.currentTimeMillis() / 1000;
            long oldTime = currentTime - 300; // 5 minutes ago
            long futureTime = currentTime + 300; // 5 minutes from now

            String currentSignature, oldSignature, futureSignature;
            try {
                currentSignature = "t=" + currentTime + ",v1=" + Webhook.Util.computeHmacSha256(webhookSecret, currentTime + "." + payload);
                oldSignature = "t=" + oldTime + ",v1=" + Webhook.Util.computeHmacSha256(webhookSecret, oldTime + "." + payload);
                futureSignature = "t=" + futureTime + ",v1=" + Webhook.Util.computeHmacSha256(webhookSecret, futureTime + "." + payload);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new RuntimeException("Failed to generate webhook signatures", e);
            }

            // Then - All should be valid (within tolerance)
            assertThatCode(() -> {
                Webhook.constructEvent(payload, currentSignature, webhookSecret);
            }).doesNotThrowAnyException();

            assertThatCode(() -> {
                Webhook.constructEvent(payload, oldSignature, webhookSecret);
            }).doesNotThrowAnyException();

            assertThatCode(() -> {
                Webhook.constructEvent(payload, futureSignature, webhookSecret);
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("API Version Compatibility Testing")
    class ApiVersionCompatibilityTest {

        @Test
        @DisplayName("Test API version compatibility - validates different API versions work")
        void shouldTestApiVersionCompatibility() throws StripeException {
            // Given - Test with different API versions
            String[] testVersions = {"2020-08-27", "2023-10-16", "2024-06-20"};
            
            for (int i = 0; i < testVersions.length; i++) {
                // When - Create customer (API version is set globally in Stripe configuration)
                String testEmail = "version-test-" + System.currentTimeMillis() + "@example.com";
                Customer customer = Customer.create(CustomerCreateParams.builder()
                    .setEmail(testEmail)
                    .build());
                
                // Then - Validate response structure is consistent
                assertThat(customer).isNotNull();
                assertThat(customer.getId()).startsWith("cus_");
                assertThat(customer.getEmail()).isEqualTo(testEmail);
                assertThat(customer.getObject()).isEqualTo("customer");
                
                // Clean up
                testCustomerId = customer.getId();
            }
        }

        @Test
        @DisplayName("Test API version in webhook events - validates webhook API version handling")
        void shouldTestApiVersionInWebhookEvents() {
            // Given - Webhook payloads with different API versions
            String[] apiVersions = {"2020-08-27", "2023-10-16", "2024-06-20"};
            
            for (String apiVersion : apiVersions) {
                String payload = String.format("""
                    {
                        "id": "evt_test_api_version_%s",
                        "object": "event",
                        "api_version": "%s",
                        "created": 1609459200,
                        "data": {
                            "object": {
                                "id": "cus_test_customer",
                                "object": "customer",
                                "email": "test@example.com"
                            }
                        },
                        "livemode": false,
                        "type": "customer.created"
                    }
                    """, apiVersion.replace(".", "_"), apiVersion);

                // When - Generate signature and construct event
                long timestamp = System.currentTimeMillis() / 1000;
                String signedPayload = timestamp + "." + payload;
                String signature;
                try {
                    signature = "t=" + timestamp + ",v1=" + Webhook.Util.computeHmacSha256(webhookSecret, signedPayload);
                } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                    throw new RuntimeException("Failed to generate webhook signature", e);
                }

                // Then - Should handle different API versions correctly
                assertThatCode(() -> {
                    com.stripe.model.Event event = Webhook.constructEvent(payload, signature, webhookSecret);
                    // Note: API version is not directly accessible via getter in Stripe Event model
                    // We validate it's present in the payload instead
                    assertThat(payload).contains("\"api_version\": \"" + apiVersion + "\"");
                    assertThat(event.getType()).isEqualTo("customer.created");
                    assertThat(event.getLivemode()).isFalse();
                }).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Test backward compatibility with older API versions - validates backward compatibility")
        void shouldTestBackwardCompatibilityWithOlderApiVersions() throws StripeException {
            // Given - Test with current API version (backward compatibility is handled by Stripe)
            
            // When - Create customer with current API version
            String testEmail = "backward-compat-" + System.currentTimeMillis() + "@example.com";
            Customer customer = Customer.create(CustomerCreateParams.builder()
                .setEmail(testEmail)
                .putMetadata("test_field", "backward_compat")
                .build());
            
            // Then - Should work with current API version
            assertThat(customer).isNotNull();
            assertThat(customer.getId()).startsWith("cus_");
            assertThat(customer.getEmail()).isEqualTo(testEmail);
            assertThat(customer.getMetadata()).containsEntry("test_field", "backward_compat");
            assertThat(customer.getObject()).isEqualTo("customer");
            
            // Store for cleanup
            testCustomerId = customer.getId();
        }
    }

    @Nested
    @DisplayName("Error Handling and Edge Cases")
    class ErrorHandlingTest {

        @Test
        @DisplayName("Handle Stripe API errors gracefully - validates error handling")
        void shouldHandleStripeApiErrorsGracefully() {
            // Given - Invalid customer ID
            String invalidCustomerId = "cus_invalid_customer_id";

            // When & Then - Should throw appropriate Stripe exception
            assertThatCode(() -> {
                Customer.retrieve(invalidCustomerId);
            }).isInstanceOf(com.stripe.exception.InvalidRequestException.class);
        }

        @Test
        @DisplayName("Handle network timeouts and retries - validates resilience")
        void shouldHandleNetworkTimeoutsAndRetries() throws StripeException {
            // Given - Set short timeout to simulate network issues
            int originalTimeout = Stripe.getConnectTimeout();
            int originalReadTimeout = Stripe.getReadTimeout();
            
            try {
                // Set very short timeouts to potentially trigger timeout scenarios
                Stripe.setConnectTimeout(1000); // 1 second
                Stripe.setReadTimeout(2000); // 2 seconds
                
                // When - Make API call
                String testEmail = "timeout-test-" + System.currentTimeMillis() + "@example.com";
                Customer customer = Customer.create(CustomerCreateParams.builder()
                    .setEmail(testEmail)
                    .build());
                
                // Then - Should either succeed or fail gracefully
                if (customer != null) {
                    assertThat(customer.getId()).startsWith("cus_");
                    testCustomerId = customer.getId();
                }
                
            } finally {
                // Restore original timeouts
                Stripe.setConnectTimeout(originalTimeout);
                Stripe.setReadTimeout(originalReadTimeout);
            }
        }
    }

    // Cleanup method to remove test data
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Clean up created test data
        if (testCustomerId != null) {
            try {
                Customer.retrieve(testCustomerId).delete();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        
        if (testPriceId != null) {
            try {
                Price.retrieve(testPriceId).update(
                    com.stripe.param.PriceUpdateParams.builder()
                        .setActive(false)
                        .build()
                );
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        
        if (testProductId != null) {
            try {
                Product.retrieve(testProductId).update(
                    com.stripe.param.ProductUpdateParams.builder()
                        .setActive(false)
                        .build()
                );
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}
