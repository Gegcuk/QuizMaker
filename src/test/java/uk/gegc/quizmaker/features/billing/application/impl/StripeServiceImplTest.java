package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import com.stripe.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse;
import uk.gegc.quizmaker.features.billing.api.dto.SubscriptionResponse;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeService Tests")
class StripeServiceImplTest {

    @Mock
    private StripeProperties stripeProperties;

    @Mock
    private StripeClient stripeClient;

    @Mock
    private com.stripe.service.checkout.SessionService sessionService;

    @Mock
    private CustomerService customerService;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private ChargeService chargeService;

    @Mock
    private PriceService priceService;

    private StripeServiceImpl stripeService;

    @BeforeEach
    void setUp() {
        stripeService = new StripeServiceImpl(stripeProperties);
    }

    @Nested
    @DisplayName("createCheckoutSession Tests")
    class CreateCheckoutSessionTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            var mockCheckoutService = mock(com.stripe.service.CheckoutService.class);
            lenient().when(stripeClient.checkout()).thenReturn(mockCheckoutService);
            lenient().when(mockCheckoutService.sessions()).thenReturn(sessionService);
        }

        @Test
        @DisplayName("should create checkout session successfully with packId")
        void createCheckoutSession_validInputWithPackId_createsSession() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            UUID packId = UUID.randomUUID();
            String priceId = "price_123";
            String successUrl = "https://example.com/success";
            String cancelUrl = "https://example.com/cancel";

            when(stripeProperties.getSuccessUrl()).thenReturn(successUrl);
            when(stripeProperties.getCancelUrl()).thenReturn(cancelUrl);

            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn("cs_test_123");
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_123");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(mockSession);

            // When
            CheckoutSessionResponse result = stripeService.createCheckoutSession(userId, priceId, packId);

            // Then
            assertThat(result.sessionId()).isEqualTo("cs_test_123");
            assertThat(result.url()).isEqualTo("https://checkout.stripe.com/pay/cs_test_123");
            verify(sessionService).create(any(SessionCreateParams.class));
        }

        @Test
        @DisplayName("should create checkout session successfully without packId")
        void createCheckoutSession_validInputWithoutPackId_createsSession() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_456";
            String successUrl = "https://example.com/success";
            String cancelUrl = "https://example.com/cancel";

            when(stripeProperties.getSuccessUrl()).thenReturn(successUrl);
            when(stripeProperties.getCancelUrl()).thenReturn(cancelUrl);

            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn("cs_test_456");
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_456");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(mockSession);

            // When
            CheckoutSessionResponse result = stripeService.createCheckoutSession(userId, priceId, null);

            // Then
            assertThat(result.sessionId()).isEqualTo("cs_test_456");
            assertThat(result.url()).isEqualTo("https://checkout.stripe.com/pay/cs_test_456");
        }

        @Test
        @DisplayName("should add session_id placeholder to success URL if missing")
        void createCheckoutSession_successUrlWithoutPlaceholder_addsPlaceholder() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_789";
            String successUrl = "https://example.com/success";
            String cancelUrl = "https://example.com/cancel";

            when(stripeProperties.getSuccessUrl()).thenReturn(successUrl);
            when(stripeProperties.getCancelUrl()).thenReturn(cancelUrl);

            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn("cs_test_789");
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_789");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(mockSession);

            // When
            stripeService.createCheckoutSession(userId, priceId, null);

            // Then - verify the URL was modified
            verify(sessionService).create(any(SessionCreateParams.class));
        }

        @Test
        @DisplayName("should throw exception when priceId is null")
        void createCheckoutSession_nullPriceId_throwsException() {
            // Given
            UUID userId = UUID.randomUUID();

            // When & Then
            assertThatThrownBy(() -> stripeService.createCheckoutSession(userId, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("priceId must be provided");
        }

        @Test
        @DisplayName("should throw exception when priceId is blank")
        void createCheckoutSession_blankPriceId_throwsException() {
            // Given
            UUID userId = UUID.randomUUID();

            // When & Then
            assertThatThrownBy(() -> stripeService.createCheckoutSession(userId, "  ", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("priceId must be provided");
        }

        @Test
        @DisplayName("should throw exception when Stripe API fails")
        void createCheckoutSession_stripeApiFails_throwsException() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_fail";

            when(stripeProperties.getSuccessUrl()).thenReturn("https://example.com/success");
            when(stripeProperties.getCancelUrl()).thenReturn("https://example.com/cancel");
            StripeException stripeException = mock(StripeException.class);
            when(stripeException.getMessage()).thenReturn("API error");
            when(sessionService.create(any(SessionCreateParams.class)))
                    .thenThrow(stripeException);

            // When & Then
            assertThatThrownBy(() -> stripeService.createCheckoutSession(userId, priceId, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stripe session creation failed")
                    .hasCauseInstanceOf(StripeException.class);
        }

        @Test
        @DisplayName("should handle success URL already containing placeholder")
        void createCheckoutSession_urlAlreadyHasPlaceholder_doesNotModify() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_abc";
            String successUrl = "https://example.com/success?session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl = "https://example.com/cancel";

            when(stripeProperties.getSuccessUrl()).thenReturn(successUrl);
            when(stripeProperties.getCancelUrl()).thenReturn(cancelUrl);

            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn("cs_test_abc");
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_abc");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(mockSession);

            // When
            CheckoutSessionResponse result = stripeService.createCheckoutSession(userId, priceId, null);

            // Then
            assertThat(result.sessionId()).isEqualTo("cs_test_abc");
        }

        @Test
        @DisplayName("should handle success URL with query params")
        void createCheckoutSession_urlWithQueryParams_addsPlaceholderWithAmpersand() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_def";
            String successUrl = "https://example.com/success?ref=test";
            String cancelUrl = "https://example.com/cancel";

            when(stripeProperties.getSuccessUrl()).thenReturn(successUrl);
            when(stripeProperties.getCancelUrl()).thenReturn(cancelUrl);

            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn("cs_test_def");
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_def");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(mockSession);

            // When
            CheckoutSessionResponse result = stripeService.createCheckoutSession(userId, priceId, null);

            // Then
            assertThat(result.sessionId()).isEqualTo("cs_test_def");
        }

        @Test
        @DisplayName("should handle blank success URL")
        void createCheckoutSession_blankSuccessUrl_handlesGracefully() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_blank";
            String successUrl = "   ";
            String cancelUrl = "https://example.com/cancel";

            when(stripeProperties.getSuccessUrl()).thenReturn(successUrl);
            when(stripeProperties.getCancelUrl()).thenReturn(cancelUrl);

            Session mockSession = mock(Session.class);
            when(mockSession.getId()).thenReturn("cs_test_blank");
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_blank");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(mockSession);

            // When
            CheckoutSessionResponse result = stripeService.createCheckoutSession(userId, priceId, null);

            // Then
            assertThat(result.sessionId()).isEqualTo("cs_test_blank");
        }
    }

    @Nested
    @DisplayName("retrieveSession Tests")
    class RetrieveSessionTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            var mockCheckoutService = mock(com.stripe.service.CheckoutService.class);
            lenient().when(stripeClient.checkout()).thenReturn(mockCheckoutService);
            lenient().when(mockCheckoutService.sessions()).thenReturn(sessionService);
        }

        @Test
        @DisplayName("should retrieve session without expanding line items")
        void retrieveSession_withoutExpand_retrievesSession() throws StripeException {
            // Given
            String sessionId = "cs_test_123";
            Session mockSession = mock(Session.class);
            when(sessionService.retrieve(sessionId)).thenReturn(mockSession);

            // When
            Session result = stripeService.retrieveSession(sessionId, false);

            // Then
            assertThat(result).isEqualTo(mockSession);
            verify(sessionService).retrieve(sessionId);
        }

        @Test
        @DisplayName("should retrieve session with expanded line items")
        void retrieveSession_withExpand_retrievesSessionWithLineItems() throws StripeException {
            // Given
            String sessionId = "cs_test_456";
            Session mockSession = mock(Session.class);
            when(sessionService.retrieve(eq(sessionId), any(SessionRetrieveParams.class)))
                    .thenReturn(mockSession);

            // When
            Session result = stripeService.retrieveSession(sessionId, true);

            // Then
            assertThat(result).isEqualTo(mockSession);
            verify(sessionService).retrieve(eq(sessionId), any(SessionRetrieveParams.class));
        }
    }

    @Nested
    @DisplayName("createCustomer Tests")
    class CreateCustomerTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.customers()).thenReturn(customerService);
        }

        @Test
        @DisplayName("should create customer successfully")
        void createCustomer_validInput_createsCustomer() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String email = "test@example.com";

            Customer mockCustomer = mock(Customer.class);
            when(mockCustomer.getId()).thenReturn("cus_123");
            when(mockCustomer.getEmail()).thenReturn(email);
            when(mockCustomer.getName()).thenReturn("Test User");
            when(customerService.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);

            // When
            CustomerResponse result = stripeService.createCustomer(userId, email);

            // Then
            assertThat(result.id()).isEqualTo("cus_123");
            assertThat(result.email()).isEqualTo(email);
            assertThat(result.name()).isEqualTo("Test User");
            verify(customerService).create(any(CustomerCreateParams.class));
        }

        @Test
        @DisplayName("should throw exception when email is null")
        void createCustomer_nullEmail_throwsException() {
            // Given
            UUID userId = UUID.randomUUID();

            // When & Then
            assertThatThrownBy(() -> stripeService.createCustomer(userId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email must be provided");
        }

        @Test
        @DisplayName("should throw exception when email is blank")
        void createCustomer_blankEmail_throwsException() {
            // Given
            UUID userId = UUID.randomUUID();

            // When & Then
            assertThatThrownBy(() -> stripeService.createCustomer(userId, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email must be provided");
        }
    }

    @Nested
    @DisplayName("retrieveCustomer Tests")
    class RetrieveCustomerTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.customers()).thenReturn(customerService);
        }

        @Test
        @DisplayName("should retrieve customer successfully")
        void retrieveCustomer_validCustomerId_retrievesCustomer() throws StripeException {
            // Given
            String customerId = "cus_123";

            Customer mockCustomer = mock(Customer.class);
            when(mockCustomer.getId()).thenReturn(customerId);
            when(mockCustomer.getEmail()).thenReturn("test@example.com");
            when(mockCustomer.getName()).thenReturn("Test User");
            when(customerService.retrieve(customerId)).thenReturn(mockCustomer);

            // When
            CustomerResponse result = stripeService.retrieveCustomer(customerId);

            // Then
            assertThat(result.id()).isEqualTo(customerId);
            assertThat(result.email()).isEqualTo("test@example.com");
            assertThat(result.name()).isEqualTo("Test User");
            verify(customerService).retrieve(customerId);
        }

        @Test
        @DisplayName("should throw exception when customerId is null")
        void retrieveCustomer_nullCustomerId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.retrieveCustomer(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Customer ID must be provided");
        }

        @Test
        @DisplayName("should throw exception when customerId is blank")
        void retrieveCustomer_blankCustomerId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.retrieveCustomer("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Customer ID must be provided");
        }
    }

    @Nested
    @DisplayName("createSubscription Tests")
    class CreateSubscriptionTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.subscriptions()).thenReturn(subscriptionService);
        }

        @Test
        @DisplayName("should create subscription successfully")
        void createSubscription_validInput_createsSubscription() throws StripeException {
            // Given
            String customerId = "cus_123";
            String priceId = "price_456";

            Subscription mockSubscription = mock(Subscription.class);
            when(mockSubscription.getId()).thenReturn("sub_789");

            Invoice mockInvoice = mock(Invoice.class);
            PaymentIntent mockPaymentIntent = mock(PaymentIntent.class);
            when(mockPaymentIntent.getClientSecret()).thenReturn("pi_secret_123");
            when(mockInvoice.getPaymentIntentObject()).thenReturn(mockPaymentIntent);
            when(mockSubscription.getLatestInvoiceObject()).thenReturn(mockInvoice);

            when(subscriptionService.create(any(SubscriptionCreateParams.class)))
                    .thenReturn(mockSubscription);

            // When
            SubscriptionResponse result = stripeService.createSubscription(customerId, priceId);

            // Then
            assertThat(result.subscriptionId()).isEqualTo("sub_789");
            assertThat(result.clientSecret()).isEqualTo("pi_secret_123");
            verify(subscriptionService).create(any(SubscriptionCreateParams.class));
        }

        @Test
        @DisplayName("should throw exception when customerId is null")
        void createSubscription_nullCustomerId_throwsException() {
            // Given
            String priceId = "price_456";

            // When & Then
            assertThatThrownBy(() -> stripeService.createSubscription(null, priceId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Customer ID and Price ID must be provided");
        }

        @Test
        @DisplayName("should throw exception when priceId is null")
        void createSubscription_nullPriceId_throwsException() {
            // Given
            String customerId = "cus_123";

            // When & Then
            assertThatThrownBy(() -> stripeService.createSubscription(customerId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Customer ID and Price ID must be provided");
        }
    }

    @Nested
    @DisplayName("updateSubscription Tests")
    class UpdateSubscriptionTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.subscriptions()).thenReturn(subscriptionService);
        }

        @Test
        @DisplayName("should update subscription successfully")
        void updateSubscription_validInput_updatesSubscription() throws StripeException {
            // Given
            String subscriptionId = "sub_123";
            String newPriceId = "price_new";

            Subscription mockSubscription = mock(Subscription.class);
            SubscriptionItemCollection mockItems = mock(SubscriptionItemCollection.class);
            SubscriptionItem mockItem = mock(SubscriptionItem.class);
            when(mockItem.getId()).thenReturn("si_123");
            when(mockItems.getData()).thenReturn(List.of(mockItem));
            when(mockSubscription.getItems()).thenReturn(mockItems);

            when(subscriptionService.retrieve(subscriptionId)).thenReturn(mockSubscription);
            when(subscriptionService.update(eq(subscriptionId), any(SubscriptionUpdateParams.class)))
                    .thenReturn(mockSubscription);

            // When
            Subscription result = stripeService.updateSubscription(subscriptionId, newPriceId);

            // Then
            assertThat(result).isEqualTo(mockSubscription);
            verify(subscriptionService).retrieve(subscriptionId);
            verify(subscriptionService).update(eq(subscriptionId), any(SubscriptionUpdateParams.class));
        }

        @Test
        @DisplayName("should throw exception when subscriptionId is null")
        void updateSubscription_nullSubscriptionId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.updateSubscription(null, "price_new"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Subscription ID and new Price ID must be provided");
        }

        @Test
        @DisplayName("should throw exception when newPriceId is null")
        void updateSubscription_nullNewPriceId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.updateSubscription("sub_123", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Subscription ID and new Price ID must be provided");
        }
    }

    @Nested
    @DisplayName("cancelSubscription Tests")
    class CancelSubscriptionTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.subscriptions()).thenReturn(subscriptionService);
        }

        @Test
        @DisplayName("should cancel subscription successfully")
        void cancelSubscription_validInput_cancelsSubscription() throws StripeException {
            // Given
            String subscriptionId = "sub_123";

            Subscription mockSubscription = mock(Subscription.class);
            Subscription canceledSubscription = mock(Subscription.class);
            when(mockSubscription.cancel()).thenReturn(canceledSubscription);
            when(subscriptionService.retrieve(subscriptionId)).thenReturn(mockSubscription);

            // When
            Subscription result = stripeService.cancelSubscription(subscriptionId);

            // Then
            assertThat(result).isEqualTo(canceledSubscription);
            verify(subscriptionService).retrieve(subscriptionId);
            verify(mockSubscription).cancel();
        }

        @Test
        @DisplayName("should throw exception when subscriptionId is null")
        void cancelSubscription_nullSubscriptionId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.cancelSubscription(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Subscription ID must be provided");
        }
    }

    @Nested
    @DisplayName("retrieveSubscription Tests")
    class RetrieveSubscriptionTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.subscriptions()).thenReturn(subscriptionService);
        }

        @Test
        @DisplayName("should retrieve subscription successfully")
        void retrieveSubscription_validInput_retrievesSubscription() throws StripeException {
            // Given
            String subscriptionId = "sub_123";
            Subscription mockSubscription = mock(Subscription.class);
            when(subscriptionService.retrieve(subscriptionId)).thenReturn(mockSubscription);

            // When
            Subscription result = stripeService.retrieveSubscription(subscriptionId);

            // Then
            assertThat(result).isEqualTo(mockSubscription);
            verify(subscriptionService).retrieve(subscriptionId);
        }

        @Test
        @DisplayName("should throw exception when subscriptionId is null")
        void retrieveSubscription_nullSubscriptionId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.retrieveSubscription(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Subscription ID must be provided");
        }
    }

    @Nested
    @DisplayName("retrieveCharge Tests")
    class RetrieveChargeTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.charges()).thenReturn(chargeService);
        }

        @Test
        @DisplayName("should retrieve charge successfully")
        void retrieveCharge_validInput_retrievesCharge() throws StripeException {
            // Given
            String chargeId = "ch_123";
            Charge mockCharge = mock(Charge.class);
            when(chargeService.retrieve(chargeId)).thenReturn(mockCharge);

            // When
            Charge result = stripeService.retrieveCharge(chargeId);

            // Then
            assertThat(result).isEqualTo(mockCharge);
            verify(chargeService).retrieve(chargeId);
        }

        @Test
        @DisplayName("should throw exception when chargeId is null")
        void retrieveCharge_nullChargeId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.retrieveCharge(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Charge ID must be provided");
        }
    }

    @Nested
    @DisplayName("retrieveCustomerRaw Tests")
    class RetrieveCustomerRawTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.customers()).thenReturn(customerService);
        }

        @Test
        @DisplayName("should retrieve raw customer successfully")
        void retrieveCustomerRaw_validInput_retrievesCustomer() throws StripeException {
            // Given
            String customerId = "cus_123";
            Customer mockCustomer = mock(Customer.class);
            when(customerService.retrieve(customerId)).thenReturn(mockCustomer);

            // When
            Customer result = stripeService.retrieveCustomerRaw(customerId);

            // Then
            assertThat(result).isEqualTo(mockCustomer);
            verify(customerService).retrieve(customerId);
        }

        @Test
        @DisplayName("should throw exception when customerId is null")
        void retrieveCustomerRaw_nullCustomerId_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.retrieveCustomerRaw(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Customer ID must be provided");
        }
    }

    @Nested
    @DisplayName("resolvePriceIdByLookupKey Tests")
    class ResolvePriceIdByLookupKeyTests {

        @BeforeEach
        void setUpStripeClient() throws Exception {
            injectStripeClient();
            lenient().when(stripeClient.prices()).thenReturn(priceService);
        }

        @Test
        @DisplayName("should resolve price ID by lookup key successfully")
        void resolvePriceIdByLookupKey_validInput_resolvesPriceId() throws StripeException {
            // Given
            String lookupKey = "starter_plan";
            Price mockPrice = mock(Price.class);
            when(mockPrice.getId()).thenReturn("price_123");

            PriceCollection mockCollection = mock(PriceCollection.class);
            when(mockCollection.getData()).thenReturn(List.of(mockPrice));
            when(priceService.list(any(PriceListParams.class))).thenReturn(mockCollection);

            // When
            String result = stripeService.resolvePriceIdByLookupKey(lookupKey);

            // Then
            assertThat(result).isEqualTo("price_123");
            verify(priceService).list(any(PriceListParams.class));
        }

        @Test
        @DisplayName("should throw exception when lookupKey is null")
        void resolvePriceIdByLookupKey_nullLookupKey_throwsException() {
            // When & Then
            assertThatThrownBy(() -> stripeService.resolvePriceIdByLookupKey(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lookup key must be provided");
        }

        @Test
        @DisplayName("should throw exception when no price found for lookup key")
        void resolvePriceIdByLookupKey_noPriceFound_throwsException() throws StripeException {
            // Given
            String lookupKey = "nonexistent_plan";

            PriceCollection mockCollection = mock(PriceCollection.class);
            when(mockCollection.getData()).thenReturn(Collections.emptyList());
            when(priceService.list(any(PriceListParams.class))).thenReturn(mockCollection);

            // When & Then
            assertThatThrownBy(() -> stripeService.resolvePriceIdByLookupKey(lookupKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active price found for lookup key");
        }

        @Test
        @DisplayName("should throw exception when price list returns null")
        void resolvePriceIdByLookupKey_nullPriceList_throwsException() throws StripeException {
            // Given
            String lookupKey = "null_plan";

            PriceCollection mockCollection = mock(PriceCollection.class);
            when(mockCollection.getData()).thenReturn(null);
            when(priceService.list(any(PriceListParams.class))).thenReturn(mockCollection);

            // When & Then
            assertThatThrownBy(() -> stripeService.resolvePriceIdByLookupKey(lookupKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active price found for lookup key");
        }
    }

    @Nested
    @DisplayName("Static Stripe API Tests (when StripeClient is null)")
    class StaticStripeApiTests {

        // Note: These tests don't inject StripeClient, so they test the static API fallback paths

        @Test
        @DisplayName("should use static API for retrieveSession when StripeClient is null")
        void retrieveSession_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String sessionId = "cs_static_123";
            
            // Mock static Session.retrieve
            try (var sessionMock = mockStatic(Session.class)) {
                Session mockSession = mock(Session.class);
                sessionMock.when(() -> Session.retrieve(sessionId)).thenReturn(mockSession);

                // When
                Session result = stripeService.retrieveSession(sessionId, false);

                // Then
                assertThat(result).isEqualTo(mockSession);
                sessionMock.verify(() -> Session.retrieve(sessionId));
            }
        }

        @Test
        @DisplayName("should use static API for retrieveSession with expand when StripeClient is null")
        void retrieveSession_withoutStripeClientAndExpand_usesStaticApi() throws StripeException {
            // Given
            String sessionId = "cs_static_456";
            
            // Mock static Session.retrieve with params
            try (var sessionMock = mockStatic(Session.class)) {
                Session mockSession = mock(Session.class);
                sessionMock.when(() -> Session.retrieve(eq(sessionId), any(SessionRetrieveParams.class), eq(null)))
                        .thenReturn(mockSession);

                // When
                Session result = stripeService.retrieveSession(sessionId, true);

                // Then
                assertThat(result).isEqualTo(mockSession);
                sessionMock.verify(() -> Session.retrieve(eq(sessionId), any(SessionRetrieveParams.class), eq(null)));
            }
        }

        @Test
        @DisplayName("should use static API for createCustomer when StripeClient is null")
        void createCustomer_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String email = "static@test.com";
            
            try (var customerMock = mockStatic(Customer.class)) {
                Customer mockCustomer = mock(Customer.class);
                when(mockCustomer.getId()).thenReturn("cus_static_123");
                when(mockCustomer.getEmail()).thenReturn(email);
                when(mockCustomer.getName()).thenReturn("Static Test");
                
                customerMock.when(() -> Customer.create(any(CustomerCreateParams.class)))
                        .thenReturn(mockCustomer);

                // When
                CustomerResponse result = stripeService.createCustomer(userId, email);

                // Then
                assertThat(result.id()).isEqualTo("cus_static_123");
                assertThat(result.email()).isEqualTo(email);
                customerMock.verify(() -> Customer.create(any(CustomerCreateParams.class)));
            }
        }

        @Test
        @DisplayName("should use static API for retrieveCustomer when StripeClient is null")
        void retrieveCustomer_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String customerId = "cus_static_456";
            
            try (var customerMock = mockStatic(Customer.class)) {
                Customer mockCustomer = mock(Customer.class);
                when(mockCustomer.getId()).thenReturn(customerId);
                when(mockCustomer.getEmail()).thenReturn("static@test.com");
                when(mockCustomer.getName()).thenReturn("Static User");
                
                customerMock.when(() -> Customer.retrieve(customerId)).thenReturn(mockCustomer);

                // When
                CustomerResponse result = stripeService.retrieveCustomer(customerId);

                // Then
                assertThat(result.id()).isEqualTo(customerId);
                customerMock.verify(() -> Customer.retrieve(customerId));
            }
        }

        @Test
        @DisplayName("should use static API for retrieveCustomerRaw when StripeClient is null")
        void retrieveCustomerRaw_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String customerId = "cus_static_789";
            
            try (var customerMock = mockStatic(Customer.class)) {
                Customer mockCustomer = mock(Customer.class);
                customerMock.when(() -> Customer.retrieve(customerId)).thenReturn(mockCustomer);

                // When
                Customer result = stripeService.retrieveCustomerRaw(customerId);

                // Then
                assertThat(result).isEqualTo(mockCustomer);
                customerMock.verify(() -> Customer.retrieve(customerId));
            }
        }

        @Test
        @DisplayName("should use static API for createCheckoutSession when StripeClient is null")
        void createCheckoutSession_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            UUID userId = UUID.randomUUID();
            String priceId = "price_static";
            
            when(stripeProperties.getSuccessUrl()).thenReturn("https://example.com/success");
            when(stripeProperties.getCancelUrl()).thenReturn("https://example.com/cancel");
            
            try (var sessionMock = mockStatic(Session.class)) {
                Session mockSession = mock(Session.class);
                when(mockSession.getId()).thenReturn("cs_static_123");
                when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/static");
                
                sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                        .thenReturn(mockSession);

                // When
                CheckoutSessionResponse result = stripeService.createCheckoutSession(userId, priceId, null);

                // Then
                assertThat(result.sessionId()).isEqualTo("cs_static_123");
                sessionMock.verify(() -> Session.create(any(SessionCreateParams.class)));
            }
        }

        @Test
        @DisplayName("should use static API for createSubscription when StripeClient is null")
        void createSubscription_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String customerId = "cus_static";
            String priceId = "price_static";
            
            try (var subscriptionMock = mockStatic(Subscription.class)) {
                Subscription mockSubscription = mock(Subscription.class);
                when(mockSubscription.getId()).thenReturn("sub_static_123");
                
                Invoice mockInvoice = mock(Invoice.class);
                PaymentIntent mockPaymentIntent = mock(PaymentIntent.class);
                when(mockPaymentIntent.getClientSecret()).thenReturn("pi_static_secret");
                when(mockInvoice.getPaymentIntentObject()).thenReturn(mockPaymentIntent);
                when(mockSubscription.getLatestInvoiceObject()).thenReturn(mockInvoice);
                
                subscriptionMock.when(() -> Subscription.create(any(SubscriptionCreateParams.class)))
                        .thenReturn(mockSubscription);

                // When
                SubscriptionResponse result = stripeService.createSubscription(customerId, priceId);

                // Then
                assertThat(result.subscriptionId()).isEqualTo("sub_static_123");
                subscriptionMock.verify(() -> Subscription.create(any(SubscriptionCreateParams.class)));
            }
        }

        @Test
        @DisplayName("should use static API for updateSubscription when StripeClient is null")
        void updateSubscription_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String subscriptionId = "sub_static";
            String newPriceId = "price_static_new";
            
            try (var subscriptionMock = mockStatic(Subscription.class)) {
                Subscription mockSubscription = mock(Subscription.class);
                SubscriptionItemCollection mockItems = mock(SubscriptionItemCollection.class);
                SubscriptionItem mockItem = mock(SubscriptionItem.class);
                when(mockItem.getId()).thenReturn("si_static");
                when(mockItems.getData()).thenReturn(List.of(mockItem));
                when(mockSubscription.getItems()).thenReturn(mockItems);
                when(mockSubscription.update(any(SubscriptionUpdateParams.class))).thenReturn(mockSubscription);
                
                subscriptionMock.when(() -> Subscription.retrieve(subscriptionId))
                        .thenReturn(mockSubscription);

                // When
                Subscription result = stripeService.updateSubscription(subscriptionId, newPriceId);

                // Then
                assertThat(result).isEqualTo(mockSubscription);
                subscriptionMock.verify(() -> Subscription.retrieve(subscriptionId));
            }
        }

        @Test
        @DisplayName("should use static API for cancelSubscription when StripeClient is null")
        void cancelSubscription_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String subscriptionId = "sub_cancel_static";
            
            try (var subscriptionMock = mockStatic(Subscription.class)) {
                Subscription mockSubscription = mock(Subscription.class);
                Subscription canceledSubscription = mock(Subscription.class);
                when(mockSubscription.cancel()).thenReturn(canceledSubscription);
                
                subscriptionMock.when(() -> Subscription.retrieve(subscriptionId))
                        .thenReturn(mockSubscription);

                // When
                Subscription result = stripeService.cancelSubscription(subscriptionId);

                // Then
                assertThat(result).isEqualTo(canceledSubscription);
                subscriptionMock.verify(() -> Subscription.retrieve(subscriptionId));
            }
        }

        @Test
        @DisplayName("should use static API for retrieveSubscription when StripeClient is null")
        void retrieveSubscription_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String subscriptionId = "sub_retrieve_static";
            
            try (var subscriptionMock = mockStatic(Subscription.class)) {
                Subscription mockSubscription = mock(Subscription.class);
                subscriptionMock.when(() -> Subscription.retrieve(subscriptionId))
                        .thenReturn(mockSubscription);

                // When
                Subscription result = stripeService.retrieveSubscription(subscriptionId);

                // Then
                assertThat(result).isEqualTo(mockSubscription);
                subscriptionMock.verify(() -> Subscription.retrieve(subscriptionId));
            }
        }

        @Test
        @DisplayName("should use static API for retrieveCharge when StripeClient is null")
        void retrieveCharge_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String chargeId = "ch_static_123";
            
            try (var chargeMock = mockStatic(Charge.class)) {
                Charge mockCharge = mock(Charge.class);
                chargeMock.when(() -> Charge.retrieve(chargeId)).thenReturn(mockCharge);

                // When
                Charge result = stripeService.retrieveCharge(chargeId);

                // Then
                assertThat(result).isEqualTo(mockCharge);
                chargeMock.verify(() -> Charge.retrieve(chargeId));
            }
        }

        @Test
        @DisplayName("should use static API for resolvePriceIdByLookupKey when StripeClient is null")
        void resolvePriceIdByLookupKey_withoutStripeClient_usesStaticApi() throws StripeException {
            // Given
            String lookupKey = "static_plan";
            
            try (var priceMock = mockStatic(Price.class)) {
                Price mockPrice = mock(Price.class);
                when(mockPrice.getId()).thenReturn("price_static_123");
                
                PriceCollection mockCollection = mock(PriceCollection.class);
                when(mockCollection.getData()).thenReturn(List.of(mockPrice));
                
                priceMock.when(() -> Price.list(any(PriceListParams.class)))
                        .thenReturn(mockCollection);

                // When
                String result = stripeService.resolvePriceIdByLookupKey(lookupKey);

                // Then
                assertThat(result).isEqualTo("price_static_123");
                priceMock.verify(() -> Price.list(any(PriceListParams.class)));
            }
        }
    }

    // Helper method to inject StripeClient using reflection
    private void injectStripeClient() throws Exception {
        var field = StripeServiceImpl.class.getDeclaredField("stripeClient");
        field.setAccessible(true);
        field.set(stripeService, stripeClient);
    }
}

