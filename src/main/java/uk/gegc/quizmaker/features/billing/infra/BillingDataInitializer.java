package uk.gegc.quizmaker.features.billing.infra;

import com.stripe.StripeClient;
import com.stripe.model.Price;
import org.springframework.boot.CommandLineRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Seeds default ProductPack records at startup based on configured Stripe Price IDs.
 * This enables MVP checkout flows without manual DB seeding.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingDataInitializer implements CommandLineRunner {

    private final ProductPackRepository productPackRepository;
    private final StripeProperties stripeProperties;

    @Autowired(required = false)
    private StripeClient stripeClient;

    @Override
    public void run(String... args) {
        try {
            log.info("BillingDataInitializer: seeding packs from Stripe price IDs if missing...");
            seedFromPriceId(stripeProperties.getPriceSmall(), "Starter Pack", 1000L);
            seedFromPriceId(stripeProperties.getPriceMedium(), "Growth Pack", 5000L);
            seedFromPriceId(stripeProperties.getPriceLarge(), "Pro Pack", 10000L);
        } catch (Exception e) {
            log.warn("BillingDataInitializer encountered an error while seeding packs: {}", e.getMessage());
        }
    }

    private void seedFromPriceId(String priceId, String defaultName, long defaultTokens) {
        if (!StringUtils.hasText(priceId)) {
            return;
        }

        Optional<ProductPack> existing = productPackRepository.findByStripePriceId(priceId);
        if (existing.isPresent()) {
            return;
        }

        String name = defaultName;
        long tokens = defaultTokens;
        long amountCents = 0L;
        String currency = "usd";

        try {
            Price price = (stripeClient != null)
                    ? stripeClient.prices().retrieve(priceId)
                    : Price.retrieve(priceId);

            if (price != null) {
                if (price.getUnitAmount() != null) {
                    amountCents = price.getUnitAmount();
                }
                if (StringUtils.hasText(price.getCurrency())) {
                    currency = price.getCurrency();
                }
                if (StringUtils.hasText(price.getNickname())) {
                    name = price.getNickname();
                }

                // Try metadata for tokens (prefer price metadata, fallback to product metadata)
                Long tokensFromMeta = extractTokensFromMetadata(price.getMetadata());
                if (tokensFromMeta == null && price.getProductObject() != null) {
                    tokensFromMeta = extractTokensFromMetadata(price.getProductObject().getMetadata());
                    if (StringUtils.hasText(price.getProductObject().getName())) {
                        name = price.getProductObject().getName();
                    }
                }
                if (tokensFromMeta != null && tokensFromMeta > 0) {
                    tokens = tokensFromMeta;
                }
            }
        } catch (Exception e) {
            log.info("Could not retrieve Stripe Price {} (using defaults): {}", priceId, e.getMessage());
        }

        ProductPack pack = new ProductPack();
        pack.setId(UUID.randomUUID());
        pack.setName(name);
        pack.setTokens(tokens);
        pack.setPriceCents(amountCents);
        pack.setCurrency(currency);
        pack.setStripePriceId(priceId);
        productPackRepository.save(pack);

        log.info("Seeded ProductPack '{}': tokens={}, amountCents={}, currency={}, priceId={}",
                name, tokens, amountCents, currency, priceId);
    }

    private Long extractTokensFromMetadata(Map<String, String> metadata) {
        if (metadata == null) return null;
        String val = metadata.get("tokens");
        if (!StringUtils.hasText(val)) return null;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
