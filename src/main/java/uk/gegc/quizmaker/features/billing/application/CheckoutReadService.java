package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionStatus;
import uk.gegc.quizmaker.features.billing.api.dto.ConfigResponse;
import uk.gegc.quizmaker.features.billing.api.dto.PackDto;

import java.util.List;
import java.util.UUID;

/**
 * Service for reading checkout-related data.
 * Handles fetching checkout session status and configuration data.
 */
public interface CheckoutReadService {

    /**
     * Get checkout session status for a specific session ID.
     * 
     * @param sessionId the Stripe checkout session ID
     * @param userId the current user ID (for authorization)
     * @return checkout session status
     */
    CheckoutSessionStatus getCheckoutSessionStatus(String sessionId, UUID userId);

    /**
     * Get billing configuration including publishable key and available packs.
     * 
     * @return billing configuration
     */
    ConfigResponse getBillingConfig();

    /**
     * Get all available product packs.
     * 
     * @return list of available product packs
     */
    List<PackDto> getAvailablePacks();
}
