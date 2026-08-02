package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.model.LineItem;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates that one Stripe line item matches one active server-owned token pack. */
@Service
@RequiredArgsConstructor
public class CheckoutValidationServiceImpl implements CheckoutValidationService {

    private final ProductPackRepository productPackRepository;

    @Override
    public CheckoutValidationResult validateAndResolvePack(Session session, UUID packIdFromMetadata) {
        if (session == null) {
            throw invalid("Checkout session is missing");
        }

        LineItem lineItem = requireSingleLineItem(session);
        String priceId = requirePriceId(lineItem);
        ProductPack pack = productPackRepository.findByStripePriceId(priceId)
                .filter(ProductPack::isActive)
                .orElseThrow(() -> invalid("Stripe line item does not map to an active token pack"));

        crossCheckMetadata(session.getMetadata(), packIdFromMetadata, pack, priceId);
        requireMatchingAmount(session, pack);
        requireMatchingCurrency(session, lineItem, pack);

        return new CheckoutValidationResult(pack, null, pack.getPriceCents(), pack.getCurrency(), pack.getTokens(), false);
    }

    private LineItem requireSingleLineItem(Session session) {
        if (session.getLineItems() == null) {
            throw invalid("Checkout session line items were not expanded");
        }
        List<LineItem> lineItems = session.getLineItems().getData();
        if (lineItems == null || lineItems.size() != 1 || lineItems.get(0) == null) {
            throw invalid("Checkout session must contain exactly one token-pack line item");
        }
        return lineItems.get(0);
    }

    private String requirePriceId(LineItem lineItem) {
        if (lineItem.getPrice() == null || !StringUtils.hasText(lineItem.getPrice().getId())) {
            throw invalid("Checkout line item is missing its Stripe price");
        }
        return lineItem.getPrice().getId();
    }

    private void crossCheckMetadata(Map<String, String> metadata, UUID packIdFromMetadata, ProductPack pack, String lineItemPriceId) {
        if (packIdFromMetadata != null && !packIdFromMetadata.equals(pack.getId())) {
            throw invalid("Checkout metadata pack does not match the Stripe line item");
        }
        if (metadata != null && StringUtils.hasText(metadata.get("priceId"))
                && !metadata.get("priceId").equals(lineItemPriceId)) {
            throw invalid("Checkout metadata price does not match the Stripe line item");
        }
    }

    private void requireMatchingAmount(Session session, ProductPack pack) {
        if (session.getAmountTotal() == null || session.getAmountTotal() != pack.getPriceCents()) {
            throw invalid("Checkout amount does not match the configured token pack");
        }
    }

    private void requireMatchingCurrency(Session session, LineItem lineItem, ProductPack pack) {
        if (!sameCurrency(session.getCurrency(), pack.getCurrency()) || !sameCurrency(lineItem.getCurrency(), pack.getCurrency())) {
            throw invalid("Checkout currency does not match the configured token pack");
        }
    }

    private boolean sameCurrency(String actual, String expected) {
        return StringUtils.hasText(actual) && StringUtils.hasText(expected) && actual.equalsIgnoreCase(expected);
    }

    private InvalidCheckoutSessionException invalid(String message) {
        return new InvalidCheckoutSessionException(message);
    }
}
