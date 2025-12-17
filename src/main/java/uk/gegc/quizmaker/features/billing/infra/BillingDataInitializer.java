package uk.gegc.quizmaker.features.billing.infra;

import com.stripe.StripeClient;
import com.stripe.model.Price;
import com.stripe.param.PriceRetrieveParams;
import org.springframework.boot.CommandLineRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
            log.info("Resolved Stripe price IDs: small='{}' medium='{}' large='{}'", 
                    stripeProperties.getPriceSmall(), stripeProperties.getPriceMedium(), stripeProperties.getPriceLarge());

            long before = productPackRepository.count();
            seedFromPriceId(stripeProperties.getPriceSmall(), "Starter Pack", 1000L);
            seedFromPriceId(stripeProperties.getPriceMedium(), "Growth Pack", 5000L);
            seedFromPriceId(stripeProperties.getPriceLarge(), "Pro Pack", 10000L);
            long after = productPackRepository.count();
            log.info("BillingDataInitializer: product_packs count before={} after={}", before, after);
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
        String description = null;
        long tokens = defaultTokens;
        long amountCents = 0L;
        String currency = "usd";

        try {
            PriceRetrieveParams retrieveParams = PriceRetrieveParams.builder()
                    .addExpand("product")
                    .build();

            Price price = (stripeClient != null)
                    ? stripeClient.prices().retrieve(priceId, retrieveParams)
                    : Price.retrieve(priceId, retrieveParams, null);

            if (price != null) {
                if (price.getUnitAmount() != null) {
                    amountCents = price.getUnitAmount();
                }
                if (StringUtils.hasText(price.getCurrency())) {
                    currency = price.getCurrency();
                }
                if (StringUtils.hasText(price.getNickname())) {
                    name = price.getNickname();
                } else if (price.getProductObject() != null && StringUtils.hasText(price.getProductObject().getName())) {
                    name = price.getProductObject().getName();
                }

	                if (price.getProductObject() != null && StringUtils.hasText(price.getProductObject().getDescription())) {
	                    description = price.getProductObject().getDescription();
	                }
	                if (!StringUtils.hasText(description) && price.getMetadata() != null) {
	                    String fromPriceMeta = price.getMetadata().get("description");
	                    if (StringUtils.hasText(fromPriceMeta)) {
	                        description = fromPriceMeta;
	                    }
	                }
	                if (!StringUtils.hasText(description) && price.getProductObject() != null && price.getProductObject().getMetadata() != null) {
	                    String fromMeta = price.getProductObject().getMetadata().get("description");
	                    if (StringUtils.hasText(fromMeta)) {
	                        description = fromMeta;
	                    }
	                }

	                // Try metadata for tokens (prefer price metadata, fallback to product metadata)
	                Long tokensFromMeta = extractTokensFromMetadata(price.getMetadata());
	                if (tokensFromMeta == null && price.getProductObject() != null) {
	                    tokensFromMeta = extractTokensFromMetadata(price.getProductObject().getMetadata());
	                }
	                if (tokensFromMeta != null && tokensFromMeta > 0) {
	                    tokens = tokensFromMeta;
	                }
	            }
        } catch (Exception e) {
            log.info("Could not retrieve Stripe Price {} (using defaults): {}", priceId, e.getMessage());
        }

        ProductPack pack = new ProductPack();
        pack.setName(name);
        pack.setDescription(description);
        pack.setTokens(tokens);
        pack.setPriceCents(amountCents);
        pack.setCurrency(currency);
        pack.setStripePriceId(priceId);
        pack.setActive(true);
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
