package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.util.UUID;

/** Resolves one active, server-owned token pack for a checkout request. */
public interface CheckoutPackResolver {

    /**
     * Resolves the preferred pack ID or the temporary legacy Stripe price ID.
     * When both identifiers are present, they must identify the same active pack.
     */
    ProductPack resolve(UUID packId, String legacyPriceId);
}
