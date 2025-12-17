package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.param.PriceListParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.StripePackSyncService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.*;

/**
 * Default implementation of {@link StripePackSyncService}.
 * <p>
 * Periodically synchronizes ProductPack records with active Stripe Prices that
 * represent token packs (identified via metadata).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripePackSyncServiceImpl implements StripePackSyncService {

    private final ProductPackRepository productPackRepository;
    private final StripeProperties stripeProperties;
    private final BillingProperties billingProperties;

    @Autowired(required = false)
    private StripeClient stripeClient;

    @Override
    @Transactional
    public void syncActivePacks() {
        if (!StringUtils.hasText(stripeProperties.getSecretKey())) {
            log.debug("StripePackSyncService: secret key not configured; skipping pack sync");
            return;
        }

        try {
            log.info("StripePackSyncService: starting sync of ProductPacks from Stripe Prices");

            List<ProductPack> existingPacks = productPackRepository.findAll();
            Map<String, ProductPack> packsByPriceId = new HashMap<>();
            for (ProductPack pack : existingPacks) {
                packsByPriceId.put(pack.getStripePriceId(), pack);
            }

            List<Price> stripePrices = fetchActiveTokenPackPrices();
            if (stripePrices.isEmpty()) {
                log.warn("StripePackSyncService: no active token-pack prices found in Stripe; leaving existing packs unchanged");
                return;
            }

            Set<String> seenPriceIds = new HashSet<>();
            String desiredCurrency = billingProperties.getCurrency();

            for (Price price : stripePrices) {
                String priceId = price.getId();
                if (!StringUtils.hasText(priceId)) {
                    continue;
                }

                Long tokens = extractTokens(price);
                if (tokens == null || tokens <= 0) {
                    continue;
                }

                String currency = price.getCurrency();
                if (StringUtils.hasText(desiredCurrency) && StringUtils.hasText(currency)) {
                    if (!currency.equalsIgnoreCase(desiredCurrency)) {
                        continue;
                    }
                }

                String name = resolveName(price);
                String description = resolveDescription(price);
                long amountCents = price.getUnitAmount() != null ? price.getUnitAmount() : 0L;
                if (!StringUtils.hasText(currency)) {
                    currency = desiredCurrency;
                }
                if (!StringUtils.hasText(currency)) {
                    currency = "usd";
                }

                seenPriceIds.add(priceId);

                ProductPack pack = packsByPriceId.get(priceId);
                boolean isNew = false;
                if (pack == null) {
                    pack = new ProductPack();
                    isNew = true;
                }

                pack.setName(name);
                pack.setDescription(description);
                pack.setTokens(tokens);
                pack.setPriceCents(amountCents);
                pack.setCurrency(currency.toLowerCase(Locale.ROOT));
                pack.setStripePriceId(priceId);
                pack.setActive(true);

                productPackRepository.save(pack);

                if (isNew) {
                    log.info("StripePackSyncService: created ProductPack '{}' (priceId={}, tokens={}, amountCents={}, currency={})",
                            name, priceId, tokens, amountCents, currency);
                } else {
                    log.info("StripePackSyncService: updated ProductPack '{}' (priceId={}, tokens={}, amountCents={}, currency={})",
                            name, priceId, tokens, amountCents, currency);
                }
            }

            // Deactivate packs whose Stripe Price is no longer active
            for (ProductPack existing : existingPacks) {
                if (!seenPriceIds.contains(existing.getStripePriceId()) && existing.isActive()) {
                    existing.setActive(false);
                    productPackRepository.save(existing);
                    log.info("StripePackSyncService: deactivated ProductPack '{}' (priceId={})",
                            existing.getName(), existing.getStripePriceId());
                }
            }

            log.info("StripePackSyncService: completed sync of ProductPacks from Stripe");
        } catch (Exception e) {
            log.warn("StripePackSyncService: failed to sync ProductPacks from Stripe: {}", e.getMessage(), e);
        }
    }

    private List<Price> fetchActiveTokenPackPrices() throws StripeException {
        PriceListParams params = PriceListParams.builder()
                .setActive(true)
                .setLimit(100L)
                .addExpand("data.product")
                .build();

        List<Price> prices;
        if (stripeClient != null) {
            prices = stripeClient.prices().list(params).getData();
        } else {
            prices = Price.list(params).getData();
        }

        if (prices == null) {
            return Collections.emptyList();
        }

        // Filter to prices that look like token packs (have tokens metadata)
        List<Price> result = new ArrayList<>();
        for (Price price : prices) {
            Long tokens = extractTokens(price);
            if (tokens != null && tokens > 0) {
                result.add(price);
            }
        }
        return result;
    }

    private Long extractTokens(Price price) {
        Long fromPrice = extractTokensFromMetadata(price.getMetadata());
        if (fromPrice != null && fromPrice > 0) {
            return fromPrice;
        }
        if (price.getProductObject() != null) {
            return extractTokensFromMetadata(price.getProductObject().getMetadata());
        }
        return null;
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

    private String resolveName(Price price) {
        if (StringUtils.hasText(price.getNickname())) {
            return price.getNickname();
        }
        if (price.getProductObject() != null && StringUtils.hasText(price.getProductObject().getName())) {
            return price.getProductObject().getName();
        }
        return "Token Pack";
    }

    private String resolveDescription(Price price) {
        if (price == null) return null;

        if (price.getProductObject() != null && StringUtils.hasText(price.getProductObject().getDescription())) {
            return price.getProductObject().getDescription();
        }

        if (price.getMetadata() != null) {
            String fromPrice = price.getMetadata().get("description");
            if (StringUtils.hasText(fromPrice)) {
                return fromPrice;
            }
        }

        if (price.getProductObject() != null && price.getProductObject().getMetadata() != null) {
            String fromProductMeta = price.getProductObject().getMetadata().get("description");
            if (StringUtils.hasText(fromProductMeta)) {
                return fromProductMeta;
            }
        }

        return null;
    }
}
