package uk.gegc.quizmaker.features.billing.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.billing.api.dto.*;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.infra.mapping.PaymentMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import com.stripe.model.StripeObject;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingCheckoutController {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final StripeService stripeService;
    private final ProductPackRepository productPackRepository;

    @GetMapping("/checkout-sessions/{sessionId}")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<CheckoutSessionStatus> getCheckoutSessionStatus(@PathVariable String sessionId) {
        var payment = paymentRepository.findByStripeSessionId(sessionId)
                .orElseThrow(() -> new InvalidCheckoutSessionException("Checkout session not found"));
        return ResponseEntity.ok(paymentMapper.toCheckoutSessionStatus(payment));
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<ConfigResponse> getConfig() {
        // Get publishable key from Stripe properties (would need to be injected)
        String publishableKey = "pk_test_..."; // This should come from configuration
        
        // Get available product packs as prices
        List<PackDto> prices = productPackRepository.findAll().stream()
                .map(pack -> new PackDto(
                        pack.getId(),
                        pack.getName(),
                        pack.getTokens(),
                        pack.getPriceCents(),
                        pack.getCurrency(),
                        pack.getStripePriceId()
                ))
                .toList();
        
        return ResponseEntity.ok(new ConfigResponse(publishableKey, prices));
    }

    @PostMapping("/create-customer")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody CreateCustomerRequest request) {
        try {
            // In a real implementation, you'd get the current user ID from security context
            UUID userId = UUID.randomUUID(); // Placeholder - should come from authentication
            
            CustomerResponse customer = stripeService.createCustomer(userId, request.email());
            return ResponseEntity.ok(customer);
        } catch (CardException e) {
            // Handle card-specific errors like in the Stripe example
            return ResponseEntity.badRequest().build();
        } catch (StripeException e) {
            // Handle other Stripe API errors
            return ResponseEntity.status(500).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/customers/{customerId}")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId) {
        try {
            CustomerResponse customer = stripeService.retrieveCustomer(customerId);
            return ResponseEntity.ok(customer);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/create-subscription")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<SubscriptionResponse> createSubscription(@RequestBody CreateSubscriptionRequest request) {
        try {
            // In a real implementation, you'd get the customer ID from the authenticated user
            String customerId = "cus_placeholder"; // This should come from user's stored customer ID
            
            SubscriptionResponse subscription = stripeService.createSubscription(customerId, request.priceId());
            return ResponseEntity.ok(subscription);
        } catch (CardException e) {
            // Handle card-specific errors like in the Stripe example
            return ResponseEntity.badRequest().build();
        } catch (StripeException e) {
            // Handle other Stripe API errors
            return ResponseEntity.status(500).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/update-subscription")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<String> updateSubscription(@RequestBody UpdateSubscriptionRequest request) {
        try {
            // In a real implementation, you'd resolve the price ID from the lookup key
            String newPriceId = request.newPriceLookupKey(); // This should be resolved from lookup key
            
            var subscription = stripeService.updateSubscription(request.subscriptionId(), newPriceId);
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/cancel-subscription")
    @PreAuthorize("hasAuthority('billing:write')")
    public ResponseEntity<String> cancelSubscription(@RequestBody CancelSubscriptionRequest request) {
        try {
            var subscription = stripeService.cancelSubscription(request.subscriptionId());
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}

