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
        return productPackRepository.findAll().stream()
                .map(pack -> new PackDto(
                        pack.getId(),
                        pack.getName(),
                        pack.getTokens(),
                        pack.getPriceCents(),
                        pack.getCurrency(),
                        pack.getStripePriceId()
                ))
                .toList();
    }
}
