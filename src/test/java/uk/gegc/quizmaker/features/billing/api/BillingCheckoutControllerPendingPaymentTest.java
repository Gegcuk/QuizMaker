package uk.gegc.quizmaker.features.billing.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CreateCheckoutSessionRequest;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingCheckoutControllerPendingPaymentTest {

    @Mock
    private BillingService billingService;
    @Mock
    private CheckoutReadService checkoutReadService;
    @Mock
    private StripeService stripeService;
    @Mock
    private EstimationService estimationService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ProductPackRepository productPackRepository;
    @Mock
    private BillingProperties billingProperties;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private BillingCheckoutController controller;

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @BeforeEach
    void setUp() {
        when(authentication.getName()).thenReturn(USER_ID.toString());
        when(featureFlags.isBilling()).thenReturn(true);
    }

    @Test
    void createCheckoutSession_seedsPendingPayment_whenPackKnown() {
        UUID packId = UUID.randomUUID();
        String priceId = "price_987";
        String sessionId = "cs_test_seed";
        String sessionUrl = "https://checkout.stripe.com/test";

        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest(priceId, packId);
        doNothing().when(rateLimitService).checkRateLimit(eq("checkout-session-create"), eq(USER_ID.toString()), eq(5));
        when(stripeService.createCheckoutSession(USER_ID, priceId, packId))
                .thenReturn(new CheckoutSessionResponse(sessionUrl, sessionId));
        when(paymentRepository.findByStripeSessionId(sessionId)).thenReturn(Optional.empty());

        ProductPack pack = new ProductPack();
        pack.setId(packId);
        pack.setPriceCents(2500L);
        pack.setTokens(5000L);
        pack.setCurrency("eur");
        when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

        ResponseEntity<CheckoutSessionResponse> response = controller.createCheckoutSession(request, authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sessionId()).isEqualTo(sessionId);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();

        assertThat(saved.getStripeSessionId()).isEqualTo(sessionId);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getPackId()).isEqualTo(packId);
        assertThat(saved.getAmountCents()).isEqualTo(2500L);
        assertThat(saved.getCurrency()).isEqualTo("eur");
        assertThat(saved.getCreditedTokens()).isEqualTo(5000L);
    }

    @Test
    void createCheckoutSession_doesNotDuplicatePendingPayment_whenExists() {
        UUID packId = UUID.randomUUID();
        String priceId = "price_existing";
        String sessionId = "cs_existing";
        String sessionUrl = "https://checkout.stripe.com/existing";

        CreateCheckoutSessionRequest request = new CreateCheckoutSessionRequest(priceId, packId);
        doNothing().when(rateLimitService).checkRateLimit(eq("checkout-session-create"), eq(USER_ID.toString()), eq(5));
        when(stripeService.createCheckoutSession(USER_ID, priceId, packId))
                .thenReturn(new CheckoutSessionResponse(sessionUrl, sessionId));

        Payment existing = new Payment();
        existing.setStripeSessionId(sessionId);
        when(paymentRepository.findByStripeSessionId(sessionId)).thenReturn(Optional.of(existing));

        ResponseEntity<CheckoutSessionResponse> response = controller.createCheckoutSession(request, authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sessionId()).isEqualTo(sessionId);

        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
