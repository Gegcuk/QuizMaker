package uk.gegc.quizmaker.features.billing.application;

import com.stripe.model.checkout.Session;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.util.List;
import java.util.UUID;

/**
 * Service for validating checkout sessions and ensuring data integrity
 * in the checkout→credit path.
 */
public interface CheckoutValidationService {

    /**
     * Validates one Stripe line item and resolves its active server-owned product pack.
     * 
     * @param session the Stripe checkout session
     * @param packIdFromMetadata the metadata pack ID to cross-check against the line item (if any)
     * @return validation result with resolved pack information
     * @throws InvalidCheckoutSessionException if validation fails
     */
    CheckoutValidationResult validateAndResolvePack(Session session, UUID packIdFromMetadata);

    /**
     * Result of checkout validation containing resolved pack information.
     */
    record CheckoutValidationResult(
            ProductPack primaryPack,
            List<ProductPack> additionalPacks,
            long totalAmountCents,
            String currency,
            long totalTokens,
            boolean hasMultipleLineItems
    ) {
        /**
         * Get the total number of packs in this checkout.
         */
        public int getPackCount() {
            return 1 + (additionalPacks != null ? additionalPacks.size() : 0);
        }
        
        /**
         * Get all packs as a list.
         */
        public List<ProductPack> getAllPacks() {
            if (additionalPacks == null || additionalPacks.isEmpty()) {
                return List.of(primaryPack);
            }
            List<ProductPack> allPacks = new java.util.ArrayList<>();
            allPacks.add(primaryPack);
            allPacks.addAll(additionalPacks);
            return allPacks;
        }
    }
}
