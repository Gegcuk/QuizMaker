package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.model.LineItem;
import com.stripe.model.LineItemCollection;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationFailureReason;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Checkout snapshot validation")
class CheckoutValidationServiceImplTest {

    private static final String SESSION_ID = "cs_snapshot_123";
    private static final String PRICE_ID = "price_original";
    private static final long AMOUNT_CENTS = 2_500L;
    private static final long TOKENS = 5_000L;
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID PACK_ID = UUID.fromString("c73bcdcc-2669-4bf6-81d3-e4ae73fb11fd");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BillingMetricsService metricsService;

    private CheckoutValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CheckoutValidationServiceImpl(paymentRepository, metricsService);
    }

    @Test
    @DisplayName("returns the original purchase facts without consulting the mutable product catalog")
    void returnsOriginalPurchaseFactsFromPaymentSnapshot() {
        Payment snapshot = paymentSnapshot();
        Session session = validSession();
        when(paymentRepository.findByStripeSessionId(SESSION_ID)).thenReturn(Optional.of(snapshot));

        var result = service.validateAndResolvePack(session, PACK_ID);

        assertThat(result.packId()).isEqualTo(PACK_ID);
        assertThat(result.stripePriceId()).isEqualTo(PRICE_ID);
        assertThat(result.totalAmountCents()).isEqualTo(AMOUNT_CENTS);
        assertThat(result.totalTokens()).isEqualTo(TOKENS);
        verify(metricsService, never()).recordCheckoutValidationFailure(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("rejects a missing Stripe session before reading payment state")
    void rejectsMissingSession() {
        assertThatThrownBy(() -> service.validateAndResolvePack(null, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("session is missing");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.MISSING_SESSION);
        verify(paymentRepository, never()).findByStripeSessionId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("rejects a Stripe session without a server-created payment snapshot")
    void rejectsMissingPaymentSnapshot() {
        Session session = validSession();
        when(paymentRepository.findByStripeSessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("No server-created payment");

        verify(metricsService).recordCheckoutValidationFailure(
                CheckoutValidationFailureReason.MISSING_PAYMENT_SNAPSHOT
        );
    }

    @Test
    @DisplayName("rejects a legacy payment whose original Stripe price cannot be proven")
    void rejectsIncompleteLegacySnapshot() {
        Payment snapshot = paymentSnapshot();
        snapshot.setStripePriceIdSnapshot(null);
        when(paymentRepository.findByStripeSessionId(SESSION_ID)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.validateAndResolvePack(validSession(), PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("requires reconciliation");

        verify(metricsService).recordCheckoutValidationFailure(
                CheckoutValidationFailureReason.INCOMPLETE_PAYMENT_SNAPSHOT
        );
    }

    @Test
    @DisplayName("rejects multiple Stripe line items")
    void rejectsMultipleLineItems() {
        Session session = validSession();
        session.getLineItems().setData(List.of(
                lineItem(PRICE_ID, AMOUNT_CENTS, "eur", 1L),
                lineItem("price_extra", 100L, "eur", 1L)
        ));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("exactly one");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.LINE_ITEM_COUNT);
    }

    @Test
    @DisplayName("rejects an unexpanded Stripe line-item collection")
    void rejectsUnexpandedLineItems() {
        Session session = validSession();
        session.setLineItems(null);
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("not expanded");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.LINE_ITEM_COUNT);
    }

    @Test
    @DisplayName("rejects a line item whose Stripe price differs from the creation snapshot")
    void rejectsLineItemPriceMismatch() {
        Session session = validSession();
        session.getLineItems().setData(List.of(lineItem("price_changed", AMOUNT_CENTS, "eur", 1L)));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("line-item price");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.PRICE_MISMATCH);
    }

    @Test
    @DisplayName("rejects Stripe price metadata that differs from the creation snapshot")
    void rejectsMetadataPriceMismatch() {
        Session session = validSession();
        session.setMetadata(Map.of(
                "userId", USER_ID.toString(),
                "packId", PACK_ID.toString(),
                "priceId", "price_changed"
        ));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("metadata price");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.PRICE_MISMATCH);
    }

    @Test
    @DisplayName("rejects pack metadata that differs from the creation snapshot")
    void rejectsPackMismatch() {
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(validSession(), UUID.randomUUID()))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("metadata pack");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.PACK_MISMATCH);
    }

    @Test
    @DisplayName("rejects a client reference bound to another user")
    void rejectsUserMismatch() {
        Session session = validSession();
        session.setClientReferenceId(UUID.randomUUID().toString());
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("original purchaser");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.USER_MISMATCH);
    }

    @Test
    @DisplayName("rejects conflicting user metadata even when the client reference is correct")
    void rejectsConflictingUserMetadata() {
        Session session = validSession();
        session.setMetadata(Map.of(
                "userId", UUID.randomUUID().toString(),
                "packId", PACK_ID.toString(),
                "priceId", PRICE_ID
        ));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("original purchaser");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.USER_MISMATCH);
    }

    @Test
    @DisplayName("accepts legacy identity metadata when the client reference is absent")
    void acceptsMetadataUserWhenClientReferenceIsAbsent() {
        Session session = validSession();
        session.setClientReferenceId(null);
        stubSnapshot();

        assertThat(service.validateAndResolvePack(session, PACK_ID).packId()).isEqualTo(PACK_ID);
    }

    @Test
    @DisplayName("rejects a missing user binding")
    void rejectsMissingUserBinding() {
        Session session = validSession();
        session.setClientReferenceId(null);
        session.setMetadata(Map.of("packId", PACK_ID.toString(), "priceId", PRICE_ID));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("missing its user binding");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.USER_MISMATCH);
    }

    @Test
    @DisplayName("rejects a quantity other than one")
    void rejectsQuantityMismatch() {
        Session session = validSession();
        session.getLineItems().setData(List.of(lineItem(PRICE_ID, AMOUNT_CENTS, "eur", 2L)));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("quantity");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.QUANTITY_MISMATCH);
    }

    @Test
    @DisplayName("rejects a session total that differs from the creation snapshot")
    void rejectsSessionAmountMismatch() {
        Session session = validSession();
        session.setAmountTotal(AMOUNT_CENTS - 1);
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("amount");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("rejects a line-item total that differs from the creation snapshot")
    void rejectsLineItemAmountMismatch() {
        Session session = validSession();
        session.getLineItems().setData(List.of(lineItem(PRICE_ID, AMOUNT_CENTS - 1, "eur", 1L)));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("amount");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("rejects a Stripe currency that differs from the creation snapshot")
    void rejectsCurrencyMismatch() {
        Session session = validSession();
        session.getLineItems().setData(List.of(lineItem(PRICE_ID, AMOUNT_CENTS, "usd", 1L)));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");

        verify(metricsService).recordCheckoutValidationFailure(CheckoutValidationFailureReason.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("accepts case-insensitive currency equality")
    void acceptsCaseInsensitiveCurrency() {
        Session session = validSession();
        session.setCurrency("EUR");
        session.getLineItems().setData(List.of(lineItem(PRICE_ID, AMOUNT_CENTS, "eUr", 1L)));
        stubSnapshot();

        assertThat(service.validateAndResolvePack(session, PACK_ID).currency()).isEqualTo("eur");
    }

    @Test
    @DisplayName("rejects a null line item without attempting settlement facts")
    void rejectsNullLineItem() {
        Session session = validSession();
        session.getLineItems().setData(Collections.singletonList(null));
        stubSnapshot();

        assertThatThrownBy(() -> service.validateAndResolvePack(session, PACK_ID))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("exactly one");
    }

    private void stubSnapshot() {
        when(paymentRepository.findByStripeSessionId(SESSION_ID)).thenReturn(Optional.of(paymentSnapshot()));
    }

    private static Payment paymentSnapshot() {
        Payment payment = new Payment();
        payment.setUserId(USER_ID);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setStripeSessionId(SESSION_ID);
        payment.setPackId(PACK_ID);
        payment.setStripePriceIdSnapshot(PRICE_ID);
        payment.setAmountCents(AMOUNT_CENTS);
        payment.setCurrency("eur");
        payment.setCreditedTokens(TOKENS);
        return payment;
    }

    private static Session validSession() {
        Session session = new Session();
        LineItemCollection items = new LineItemCollection();
        items.setData(List.of(lineItem(PRICE_ID, AMOUNT_CENTS, "eur", 1L)));
        session.setId(SESSION_ID);
        session.setLineItems(items);
        session.setAmountTotal(AMOUNT_CENTS);
        session.setCurrency("eur");
        session.setClientReferenceId(USER_ID.toString());
        session.setMetadata(Map.of(
                "userId", USER_ID.toString(),
                "packId", PACK_ID.toString(),
                "priceId", PRICE_ID
        ));
        return session;
    }

    private static LineItem lineItem(String priceId, long amount, String currency, long quantity) {
        Price price = new Price();
        price.setId(priceId);
        LineItem item = new LineItem();
        item.setPrice(price);
        item.setAmountTotal(amount);
        item.setCurrency(currency);
        item.setQuantity(quantity);
        return item;
    }
}
