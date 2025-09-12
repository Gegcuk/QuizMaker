package uk.gegc.quizmaker.features.billing.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import uk.gegc.quizmaker.features.billing.api.dto.*;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import com.stripe.model.StripeObject;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Validated
public class BillingCheckoutController {

    private final CheckoutReadService checkoutReadService;
    private final StripeService stripeService;
    private final EstimationService estimationService;
    private final RateLimitService rateLimitService;

    @GetMapping("/checkout-sessions/{sessionId}")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<CheckoutSessionStatus> getCheckoutSessionStatus(@PathVariable String sessionId) {
        try {
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            CheckoutSessionStatus status = checkoutReadService.getCheckoutSessionStatus(sessionId, currentUserId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Error retrieving checkout session {}: {}", sessionId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        try {
            ConfigResponse config = checkoutReadService.getBillingConfig();
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Error retrieving billing config: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/estimate/quiz-generation")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<EstimationDto> estimateQuizGeneration(@Valid @RequestBody GenerateQuizFromDocumentRequest request) {
        try {
            // Rate limiting: 10 requests per minute per user
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            rateLimitService.checkRateLimit("quiz-estimation", currentUserId.toString(), 10);
            
            log.info("Estimating quiz generation for document: {} by user: {}", request.documentId(), currentUserId);
            
            EstimationDto estimation = estimationService.estimateQuizGeneration(request.documentId(), request);
            
            log.info("Estimation completed: {} billing tokens ({} LLM tokens) for user: {}", 
                    estimation.estimatedBillingTokens(), estimation.estimatedLlmTokens(), currentUserId);
            
            return ResponseEntity.ok(estimation);
        } catch (Exception e) {
            log.error("Error estimating quiz generation for document {}: {}", request.documentId(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/create-customer")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        try {
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            
            log.info("Creating Stripe customer for user {} with email {}", currentUserId, request.email());
            
            CustomerResponse customer = stripeService.createCustomer(currentUserId, request.email());
            return ResponseEntity.ok(customer);
        } catch (CardException e) {
            log.error("Card error creating customer: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (StripeException e) {
            log.error("Stripe API error creating customer: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        } catch (Exception e) {
            log.error("Unexpected error creating customer: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/customers/{customerId}")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId) {
        try {
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            
            // In a real implementation, you'd verify the customer belongs to the current user
            // For now, we'll log the access attempt
            log.info("User {} accessing customer {}", currentUserId, customerId);
            
            CustomerResponse customer = stripeService.retrieveCustomer(customerId);
            return ResponseEntity.ok(customer);
        } catch (Exception e) {
            log.error("Error retrieving customer {}: {}", customerId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/create-subscription")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<SubscriptionResponse> createSubscription(@Valid @RequestBody CreateSubscriptionRequest request) {
        try {
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            
            // In a real implementation, you'd get the customer ID from the authenticated user's stored data
            // For now, we'll use a placeholder - this should be resolved from user's customer ID
            String customerId = "cus_placeholder"; // TODO: Resolve from user's stored customer ID
            
            log.info("Creating subscription for user {} with customer {} and price {}", 
                    currentUserId, customerId, request.priceId());
            
            SubscriptionResponse subscription = stripeService.createSubscription(customerId, request.priceId());
            return ResponseEntity.ok(subscription);
        } catch (CardException e) {
            log.error("Card error creating subscription: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (StripeException e) {
            log.error("Stripe API error creating subscription: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        } catch (Exception e) {
            log.error("Unexpected error creating subscription: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/update-subscription")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<String> updateSubscription(@Valid @RequestBody UpdateSubscriptionRequest request) {
        try {
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            
            // In a real implementation, you'd resolve the price ID from the lookup key
            String newPriceId = request.newPriceLookupKey(); // TODO: Resolve from lookup key
            
            log.info("Updating subscription {} for user {} to price {}", 
                    request.subscriptionId(), currentUserId, newPriceId);
            
            var subscription = stripeService.updateSubscription(request.subscriptionId(), newPriceId);
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (Exception e) {
            log.error("Error updating subscription {}: {}", request.subscriptionId(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/cancel-subscription")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<String> cancelSubscription(@Valid @RequestBody CancelSubscriptionRequest request) {
        try {
            UUID currentUserId = BillingSecurityUtils.getCurrentUserId();
            
            log.info("Cancelling subscription {} for user {}", request.subscriptionId(), currentUserId);
            
            var subscription = stripeService.cancelSubscription(request.subscriptionId());
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (Exception e) {
            log.error("Error cancelling subscription {}: {}", request.subscriptionId(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}

