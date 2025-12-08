package uk.gegc.quizmaker.features.billing.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.billing.application.StripePackSyncService;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

/**
 * Scheduled job that periodically synchronizes ProductPack records with Stripe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "billing.pack-sync.enabled", havingValue = "true", matchIfMissing = true)
public class StripePackSyncScheduler {

    private final StripePackSyncService stripePackSyncService;
    private final FeatureFlags featureFlags;

    @Scheduled(fixedDelayString = "${billing.pack-sync.fixed-delay-ms:3600000}")
    public void syncPacks() {
        if (!featureFlags.isBilling()) {
            return;
        }
        try {
            stripePackSyncService.syncActivePacks();
        } catch (Exception e) {
            log.warn("StripePackSyncScheduler: error during pack sync", e);
        }
    }
}

