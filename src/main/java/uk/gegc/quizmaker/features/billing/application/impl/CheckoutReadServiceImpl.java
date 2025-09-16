package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionStatus;
import uk.gegc.quizmaker.features.billing.api.dto.ConfigResponse;
import uk.gegc.quizmaker.features.billing.api.dto.PackDto;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.infra.mapping.PaymentMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import com.stripe.model.Price;
import com.stripe.StripeClient;
import org.springframework.util.StringUtils;

/**
 * Implementation of checkout read service.
 * Handles fetching checkout session status and configuration data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutReadServiceImpl implements CheckoutReadService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final ProductPackRepository productPackRepository;
    private final StripeProperties stripeProperties;
    @Autowired(required = false)
    private StripeClient stripeClient;

    @Override
    public CheckoutSessionStatus getCheckoutSessionStatus(String sessionId, UUID userId) {
        try {
            var payment = paymentRepository.findByStripeSessionId(sessionId)
                    .orElseThrow(() -> new InvalidCheckoutSessionException("Checkout session not found"));
            
            // Ensure user can only access their own checkout sessions
            if (!payment.getUserId().equals(userId)) {
                log.warn("User {} attempted to access checkout session {} belonging to user {}", 
                        userId, sessionId, payment.getUserId());
                throw new InvalidCheckoutSessionException("Checkout session not found");
            }
            
            return paymentMapper.toCheckoutSessionStatus(payment);
        } catch (Exception e) {
            log.error("Error retrieving checkout session {}: {}", sessionId, e.getMessage());
            throw new InvalidCheckoutSessionException("Error retrieving checkout session");
        }
    }

    @Override
    public ConfigResponse getBillingConfig() {
        // Get publishable key from Stripe properties
        String publishableKey = stripeProperties.getPublishableKey();
        
        if (publishableKey == null || publishableKey.isBlank()) {
            log.warn("Stripe publishable key is not configured");
            throw new IllegalStateException("Billing configuration not available");
        }
        
        // Get available product packs
        List<PackDto> packs = getAvailablePacks();
        
        return new ConfigResponse(publishableKey, packs);
    }

    @Override
    public List<PackDto> getAvailablePacks() {
        var packs = productPackRepository.findAll().stream()
                .map(pack -> new PackDto(
                        pack.getId(),
                        pack.getName(),
                        pack.getTokens(),
                        pack.getPriceCents(),
                        pack.getCurrency(),
                        pack.getStripePriceId()
                ))
                .toList();

        if (!packs.isEmpty()) {
            return packs;
        }

        log.warn("No ProductPacks found in DB; falling back to configured Stripe price IDs for /config response");
        return buildFallbackPacksFromStripe();
    }

    private List<PackDto> buildFallbackPacksFromStripe() {
        try {
            java.util.ArrayList<PackDto> result = new java.util.ArrayList<>();
            addFallbackPack(result, stripeProperties.getPriceSmall(), "Starter Pack", 1000L);
            addFallbackPack(result, stripeProperties.getPriceMedium(), "Growth Pack", 5000L);
            addFallbackPack(result, stripeProperties.getPriceLarge(), "Pro Pack", 10000L);
            return result;
        } catch (Exception e) {
            log.warn("Failed to build fallback packs from Stripe: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private void addFallbackPack(java.util.List<PackDto> out, String priceId, String defaultName, long defaultTokens) {
        if (!StringUtils.hasText(priceId)) return;
        String name = defaultName;
        long tokens = defaultTokens;
        long amountCents = 0L;
        String currency = "usd";
        try {
            Price price = (stripeClient != null)
                    ? stripeClient.prices().retrieve(priceId)
                    : Price.retrieve(priceId);
            if (price != null) {
                if (price.getUnitAmount() != null) amountCents = price.getUnitAmount();
                if (StringUtils.hasText(price.getCurrency())) currency = price.getCurrency();
                if (price.getMetadata() != null) {
                    String t = price.getMetadata().get("tokens");
                    if (StringUtils.hasText(t)) {
                        try { tokens = Long.parseLong(t.trim()); } catch (NumberFormatException ignored) {}
                    }
                }
                if (price.getProductObject() != null) {
                    var prod = price.getProductObject();
                    if (StringUtils.hasText(prod.getName())) name = prod.getName();
                    if (prod.getMetadata() != null) {
                        String t = prod.getMetadata().get("tokens");
                        if (StringUtils.hasText(t)) {
                            try { tokens = Long.parseLong(t.trim()); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.info("Could not retrieve Stripe Price {} for fallback packs (using defaults): {}", priceId, e.getMessage());
        }
        out.add(new PackDto(UUID.randomUUID(), name, tokens, amountCents, currency, priceId));
    }
}
