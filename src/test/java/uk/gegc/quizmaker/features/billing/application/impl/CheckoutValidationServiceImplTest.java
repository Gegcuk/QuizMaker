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
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Checkout validation")
class CheckoutValidationServiceImplTest {

    @Mock
    private ProductPackRepository productPackRepository;

    private CheckoutValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CheckoutValidationServiceImpl(productPackRepository);
    }

    @Test
    @DisplayName("resolves the pack from the authoritative Stripe line item and cross-checks metadata")
    void resolvesOnlyThePackMappedFromTheStripeLineItemAndCrossChecksMetadata() {
        UUID packId = UUID.randomUUID();
        ProductPack pack = pack(packId, "price_standard", true);
        Session session = session("price_standard", 2500L, "eur", "eur", Map.of("priceId", "price_standard"));
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        var result = service.validateAndResolvePack(session, packId);

        assertThat(result.primaryPack()).isSameAs(pack);
        assertThat(result.totalAmountCents()).isEqualTo(2500L);
        assertThat(result.totalTokens()).isEqualTo(5000L);
        assertThat(result.additionalPacks()).isNull();
        assertThat(result.hasMultipleLineItems()).isFalse();
    }

    @Test
    @DisplayName("rejects a missing checkout session")
    void rejectsMissingCheckoutSession() {
        assertThatThrownBy(() -> service.validateAndResolvePack(null, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("session is missing");
    }

    @Test
    @DisplayName("rejects metadata pack that differs from the authoritative Stripe price")
    void rejectsMetadataPackThatDiffersFromTheAuthoritativeStripePrice() {
        ProductPack cheapPack = pack(UUID.randomUUID(), "price_cheap", true);
        Session session = session("price_cheap", 2500L, "eur", "eur", Map.of("priceId", "price_cheap"));
        when(productPackRepository.findByStripePriceId("price_cheap")).thenReturn(Optional.of(cheapPack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, UUID.randomUUID()))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("metadata pack");
    }

    @Test
    @DisplayName("rejects metadata price that differs from the authoritative Stripe line item")
    void rejectsMetadataPriceThatDiffersFromTheAuthoritativeStripeLineItem() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "eur", "eur", Map.of("priceId", "price_expensive"));
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, pack.getId()))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("metadata price");
    }

    @Test
    @DisplayName("rejects an amount mismatch instead of logging and continuing")
    void rejectsAmountMismatchInsteadOfLoggingAndContinuing() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 1000L, "eur", "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("rejects a currency mismatch instead of logging and continuing")
    void rejectsCurrencyMismatchInsteadOfLoggingAndContinuing() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "usd", "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("rejects multiple Stripe line items")
    void rejectsMultipleLineItems() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "eur", "eur", Map.of());
        LineItem secondItem = lineItem("price_extra", "eur");
        LineItem firstItem = lineItem("price_standard", "eur");
        LineItemCollection lineItems = new LineItemCollection();
        lineItems.setData(List.of(firstItem, secondItem));
        session.setLineItems(lineItems);

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejects inactive packs")
    void rejectsInactivePacks() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", false);
        Session session = session("price_standard", 2500L, "eur", "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("active");
    }

    @Test
    @DisplayName("rejects a session whose Stripe line items were not expanded")
    void rejectsSessionWithoutExpandedLineItems() {
        assertThatThrownBy(() -> service.validateAndResolvePack(new Session(), null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("not expanded");
    }

    @Test
    @DisplayName("rejects an empty Stripe line-item list")
    void rejectsEmptyLineItems() {
        Session session = new Session();
        LineItemCollection lineItems = new LineItemCollection();
        lineItems.setData(List.of());
        session.setLineItems(lineItems);

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejects line-item data that Stripe did not provide")
    void rejectsLineItemsWithoutData() {
        Session session = new Session();
        LineItemCollection lineItems = new LineItemCollection();
        lineItems.setData(null);
        session.setLineItems(lineItems);

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejects a null line item")
    void rejectsNullLineItem() {
        Session session = new Session();
        LineItemCollection lineItems = new LineItemCollection();
        lineItems.setData(Collections.singletonList(null));
        session.setLineItems(lineItems);

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejects a line item without a Stripe price")
    void rejectsLineItemWithoutPrice() {
        Session session = new Session();
        LineItemCollection lineItems = new LineItemCollection();
        lineItems.setData(List.of(new LineItem()));
        session.setLineItems(lineItems);

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("missing its Stripe price");
    }

    @Test
    @DisplayName("rejects a blank Stripe price ID")
    void rejectsBlankStripePriceId() {
        Session session = session(" ", 2500L, "eur", "eur", Map.of());

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("missing its Stripe price");
    }

    @Test
    @DisplayName("rejects an unknown Stripe price without falling back to metadata")
    void rejectsUnknownStripePrice() {
        Session session = session("price_unknown", 2500L, "eur", "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndResolvePack(session, UUID.randomUUID()))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("active token pack");
    }

    @Test
    @DisplayName("rejects a missing Stripe session total")
    void rejectsMissingSessionAmount() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "eur", "eur", Map.of());
        session.setAmountTotal(null);
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("rejects a missing session currency")
    void rejectsMissingSessionCurrency() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, null, "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("rejects a blank Stripe session currency")
    void rejectsBlankSessionCurrency() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, " ", "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("rejects a missing Stripe line-item currency")
    void rejectsMissingLineItemCurrency() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "eur", null, Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("rejects a line-item currency that differs from the configured pack")
    void rejectsLineItemCurrencyMismatch() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "eur", "usd", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("rejects a pack with no configured currency")
    void rejectsPackWithoutConfiguredCurrency() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        pack.setCurrency(" ");
        Session session = session("price_standard", 2500L, "eur", "eur", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> service.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("accepts case-insensitive matching Stripe currency")
    void acceptsCaseInsensitiveMatchingCurrency() {
        ProductPack pack = pack(UUID.randomUUID(), "price_standard", true);
        Session session = session("price_standard", 2500L, "EUR", "eUr", Map.of());
        when(productPackRepository.findByStripePriceId("price_standard")).thenReturn(Optional.of(pack));

        assertThat(service.validateAndResolvePack(session, null).primaryPack()).isSameAs(pack);
    }

    private static ProductPack pack(UUID id, String priceId, boolean active) {
        ProductPack pack = new ProductPack();
        pack.setId(id);
        pack.setStripePriceId(priceId);
        pack.setPriceCents(2500L);
        pack.setCurrency("eur");
        pack.setTokens(5000L);
        pack.setActive(active);
        return pack;
    }

    private static Session session(String priceId, long amount, String sessionCurrency, String lineItemCurrency, Map<String, String> metadata) {
        Session session = new Session();
        LineItemCollection items = new LineItemCollection();
        LineItem item = lineItem(priceId, lineItemCurrency);
        items.setData(List.of(item));
        session.setLineItems(items);
        session.setAmountTotal(amount);
        session.setCurrency(sessionCurrency);
        session.setMetadata(metadata);
        return session;
    }

    private static LineItem lineItem(String priceId, String currency) {
        LineItem item = new LineItem();
        Price price = new Price();
        price.setId(priceId);
        item.setPrice(price);
        item.setCurrency(currency);
        return item;
    }
}
