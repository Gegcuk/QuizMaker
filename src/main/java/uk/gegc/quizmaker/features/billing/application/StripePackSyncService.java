package uk.gegc.quizmaker.features.billing.application;

/**
 * Service responsible for synchronizing ProductPack records with Stripe Prices.
 * Keeps product_packs up-to-date and marks packs active/inactive based on Stripe.
 */
public interface StripePackSyncService {

    /**
     * Synchronize local ProductPack records with Stripe token-pack prices.
     * <p>
     * Implementation should be idempotent and safe to call frequently.
     */
    void syncActivePacks();
}

