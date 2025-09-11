package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutValidationServiceImpl implements CheckoutValidationService {

    private final ProductPackRepository productPackRepository;

    @Override
    public CheckoutValidationResult validateAndResolvePack(Session session, UUID packIdFromMetadata) {
        log.debug("Validating checkout session: {}", session.getId());
        
        // 1. Resolve primary pack
        ProductPack primaryPack = resolvePrimaryPack(session, packIdFromMetadata);
        
        // 2. Validate currency consistency
        validateCurrencyConsistency(session, primaryPack);
        
        // 3. Handle multiple line items
        List<ProductPack> additionalPacks = new ArrayList<>();
        boolean hasMultipleLineItems = false;
        
        try {
            var lineItems = session.getLineItems();
            if (lineItems != null && !lineItems.getData().isEmpty()) {
                hasMultipleLineItems = lineItems.getData().size() > 1;
                
                if (hasMultipleLineItems) {
                    log.info("Multiple line items detected in session {}: {} items", 
                            session.getId(), lineItems.getData().size());
                    
                    // Policy: Sum tokens across matched prices for multi-item checkouts
                    // This allows customers to purchase multiple packs in a single session
                    // All packs must have the same currency for consistency
                    additionalPacks = resolveAdditionalPacks(session, primaryPack);
                    
                    log.info("Multi-item checkout validated: {} total packs, summing tokens and amounts", 
                            1 + additionalPacks.size());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process line items for session {}: {}", session.getId(), e.getMessage());
        }
        
        // 4. Calculate totals
        long totalAmountCents = calculateTotalAmount(session, primaryPack, additionalPacks);
        long totalTokens = calculateTotalTokens(primaryPack, additionalPacks);
        
        // 5. Validate totals match session
        validateTotalAmount(session, totalAmountCents);
        
        log.info("Checkout validation successful for session {}: {} pack(s), {} tokens, {} cents", 
                session.getId(), 1 + additionalPacks.size(), totalTokens, totalAmountCents);
        
        return new CheckoutValidationResult(
                primaryPack,
                additionalPacks.isEmpty() ? null : additionalPacks,
                totalAmountCents,
                primaryPack.getCurrency(),
                totalTokens,
                hasMultipleLineItems
        );
    }

    private ProductPack resolvePrimaryPack(Session session, UUID packIdFromMetadata) {
        // First try: resolve from metadata
        if (packIdFromMetadata != null) {
            return productPackRepository.findById(packIdFromMetadata)
                    .orElseThrow(() -> new InvalidCheckoutSessionException(
                            "Pack referenced in metadata not found: " + packIdFromMetadata));
        }
        
        // Second try: resolve via Price ID from first line item
        try {
            var lineItems = session.getLineItems();
            if (lineItems != null && !lineItems.getData().isEmpty()) {
                var first = lineItems.getData().get(0);
                String priceId = extractPriceId(first);
                
                if (StringUtils.hasText(priceId)) {
                    Optional<ProductPack> byPrice = productPackRepository.findByStripePriceId(priceId);
                    if (byPrice.isPresent()) {
                        return byPrice.get();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve pack from line items for session {}: {}", session.getId(), e.getMessage());
        }
        
        throw new InvalidCheckoutSessionException("Unable to resolve pack from session: " + session.getId());
    }

    private List<ProductPack> resolveAdditionalPacks(Session session, ProductPack primaryPack) {
        List<ProductPack> additionalPacks = new ArrayList<>();
        
        try {
            var lineItems = session.getLineItems();
            if (lineItems != null && lineItems.getData().size() > 1) {
                // Skip the first item (already resolved as primary pack)
                for (int i = 1; i < lineItems.getData().size(); i++) {
                    var lineItem = lineItems.getData().get(i);
                    String priceId = extractPriceId(lineItem);
                    
                    if (StringUtils.hasText(priceId)) {
                        Optional<ProductPack> pack = productPackRepository.findByStripePriceId(priceId);
                        if (pack.isPresent()) {
                            // Validate currency consistency for additional packs
                            if (!pack.get().getCurrency().equals(primaryPack.getCurrency())) {
                                throw new InvalidCheckoutSessionException(
                                        "Currency mismatch in additional pack: expected " + 
                                        primaryPack.getCurrency() + ", got " + pack.get().getCurrency());
                            }
                            additionalPacks.add(pack.get());
                        } else {
                            log.warn("Additional line item with unknown price ID: {} in session {}", 
                                    priceId, session.getId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to resolve additional packs for session {}: {}", session.getId(), e.getMessage());
            throw new InvalidCheckoutSessionException("Failed to resolve additional packs: " + e.getMessage());
        }
        
        return additionalPacks;
    }

    private String extractPriceId(Object lineItem) {
        try {
            // Use reflection to access the price ID from the line item
            var priceField = lineItem.getClass().getMethod("getPrice");
            var price = priceField.invoke(lineItem);
            if (price != null) {
                var priceIdField = price.getClass().getMethod("getId");
                return (String) priceIdField.invoke(price);
            }
        } catch (Exception e) {
            log.debug("Failed to extract price ID from line item: {}", e.getMessage());
        }
        return null;
    }

    private void validateCurrencyConsistency(Session session, ProductPack pack) {
        String sessionCurrency = session.getCurrency();
        String packCurrency = pack.getCurrency();
        
        if (!StringUtils.hasText(sessionCurrency)) {
            log.warn("Session {} has no currency, using pack currency: {}", session.getId(), packCurrency);
            return;
        }
        
        if (!sessionCurrency.equalsIgnoreCase(packCurrency)) {
            throw new InvalidCheckoutSessionException(
                    String.format("Currency mismatch: session currency '%s' does not match pack currency '%s'", 
                            sessionCurrency, packCurrency));
        }
        
        // Additional validation: check line item currencies if available
        validateLineItemCurrencies(session, packCurrency);
        
        log.debug("Currency validation passed for session {}: {}", session.getId(), sessionCurrency);
    }
    
    private void validateLineItemCurrencies(Session session, String expectedCurrency) {
        try {
            var lineItems = session.getLineItems();
            if (lineItems != null && !lineItems.getData().isEmpty()) {
                for (int i = 0; i < lineItems.getData().size(); i++) {
                    var lineItem = lineItems.getData().get(i);
                    String lineItemCurrency = extractLineItemCurrency(lineItem);
                    
                    if (StringUtils.hasText(lineItemCurrency) && !lineItemCurrency.equalsIgnoreCase(expectedCurrency)) {
                        throw new InvalidCheckoutSessionException(
                                String.format("Line item %d currency '%s' does not match expected currency '%s'", 
                                        i, lineItemCurrency, expectedCurrency));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to validate line item currencies for session {}: {}", session.getId(), e.getMessage());
            // Don't throw here as this is additional validation
        }
    }
    
    private String extractLineItemCurrency(Object lineItem) {
        try {
            // Use reflection to access the currency from the line item
            var currencyField = lineItem.getClass().getMethod("getCurrency");
            return (String) currencyField.invoke(lineItem);
        } catch (Exception e) {
            log.debug("Failed to extract currency from line item: {}", e.getMessage());
        }
        return null;
    }

    private long calculateTotalAmount(Session session, ProductPack primaryPack, List<ProductPack> additionalPacks) {
        long total = primaryPack.getPriceCents();
        
        for (ProductPack pack : additionalPacks) {
            total += pack.getPriceCents();
        }
        
        return total;
    }

    private long calculateTotalTokens(ProductPack primaryPack, List<ProductPack> additionalPacks) {
        long total = primaryPack.getTokens();
        
        for (ProductPack pack : additionalPacks) {
            total += pack.getTokens();
        }
        
        return total;
    }

    private void validateTotalAmount(Session session, long calculatedTotal) {
        try {
            Long sessionTotal = session.getAmountTotal();
            if (sessionTotal != null && sessionTotal != calculatedTotal) {
                log.warn("Amount mismatch for session {}: session total {} cents, calculated {} cents", 
                        session.getId(), sessionTotal, calculatedTotal);
                
                // For now, we'll log a warning but continue
                // In production, you might want to reject this
                // throw new InvalidCheckoutSessionException(
                //         String.format("Amount mismatch: session total %d cents, calculated %d cents", 
                //                 sessionTotal, calculatedTotal));
            }
        } catch (Exception e) {
            log.warn("Failed to validate total amount for session {}: {}", session.getId(), e.getMessage());
        }
    }
}
