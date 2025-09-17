package uk.gegc.quizmaker.features.billing.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import jakarta.validation.Valid;
import uk.gegc.quizmaker.features.billing.api.dto.*;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import com.stripe.model.StripeObject;
import com.stripe.exception.StripeException;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Validated
public class BillingCheckoutController {

    private final BillingService billingService;
    private final CheckoutReadService checkoutReadService;
    private final StripeService stripeService;
    private final EstimationService estimationService;
    private final RateLimitService rateLimitService;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final BillingProperties billingProperties;

    @GetMapping("/checkout-sessions/{sessionId}")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<CheckoutSessionStatus> getCheckoutSessionStatus(@PathVariable String sessionId, Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        CheckoutSessionStatus status = checkoutReadService.getCheckoutSessionStatus(sessionId, currentUserId);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        ConfigResponse config = checkoutReadService.getBillingConfig();
        return ResponseEntity.ok(config);
    }

    @GetMapping("/balance")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<BalanceDto> getBalance(Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 60 requests per minute per user
        rateLimitService.checkRateLimit("billing-balance", currentUserId.toString(), 60);

        BalanceDto balance = billingService.getBalance(currentUserId);
        
        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=30")
                .body(balance);
    }

    @GetMapping("/transactions")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<Page<TransactionDto>> getTransactions(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) TokenTransactionType type,
            @RequestParam(required = false) TokenTransactionSource source,
            @RequestParam(required = false) LocalDateTime dateFrom,
            @RequestParam(required = false) LocalDateTime dateTo,
            Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 30 requests per minute per user
        rateLimitService.checkRateLimit("billing-transactions", currentUserId.toString(), 30);

        Page<TransactionDto> transactions = billingService.listTransactions(
                currentUserId, pageable, type, source, dateFrom, dateTo);
        
        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=60")
                .body(transactions);
    }

    @PostMapping("/estimate/quiz-generation")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<EstimationDto> estimateQuizGeneration(@Valid @RequestBody GenerateQuizFromDocumentRequest request,
                                                               Authentication authentication) {
        // Rate limiting: 10 requests per minute per user
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        rateLimitService.checkRateLimit("quiz-estimation", currentUserId.toString(), 10);
        
        log.info("Estimating quiz generation for document: {} by user: {}", request.documentId(), currentUserId);
        
        EstimationDto estimation = estimationService.estimateQuizGeneration(request.documentId(), request);
        
        log.info("Estimation completed: {} billing tokens ({} LLM tokens) for user: {}", 
                estimation.estimatedBillingTokens(), estimation.estimatedLlmTokens(), currentUserId);
        
        return ResponseEntity.ok(estimation);
    }

    @PostMapping("/checkout-sessions")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<CheckoutSessionResponse> createCheckoutSession(@Valid @RequestBody CreateCheckoutSessionRequest request,
                                                                         Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 5 requests per minute per user
        rateLimitService.checkRateLimit("checkout-session-create", currentUserId.toString(), 5);
        
        log.info("Creating checkout session for user: {} with priceId: {}", currentUserId, request.priceId());
        
        CheckoutSessionResponse session = stripeService.createCheckoutSession(
            currentUserId, 
            request.priceId(), 
            request.packId()
        );
        
        log.info("Checkout session created successfully: {} for user: {}", session.sessionId(), currentUserId);
        
        return ResponseEntity.ok(session);
    }

    @PostMapping("/create-customer")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request,
                                                           Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 3 requests per minute per user (Stripe customer creation is expensive)
        rateLimitService.checkRateLimit("billing-create-customer", currentUserId.toString(), 3);
        
        log.info("Creating Stripe customer for user {} with email {}", currentUserId, request.email());
        
        try {
            CustomerResponse customer = stripeService.createCustomer(currentUserId, request.email());
            return ResponseEntity.ok(customer);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe API error: " + e.getMessage(), e);
        }
    }

    @GetMapping("/customers/{customerId}")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId,
                                                        Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        try {
            // Verify ownership via Stripe customer metadata userId
            var rawCustomer = stripeService.retrieveCustomerRaw(customerId);
            String mdUserId = rawCustomer.getMetadata() != null ? rawCustomer.getMetadata().get("userId") : null;
            if (mdUserId == null || !mdUserId.equalsIgnoreCase(currentUserId.toString())) {
                // Check if email fallback is allowed by configuration
                if (billingProperties.isAllowEmailFallbackForCustomerOwnership()) {
                    // Fallback to email-based ownership if metadata not present and fallback is enabled
                    User user = userRepository.findById(currentUserId)
                            .orElse(null);
                    String custEmail = rawCustomer.getEmail();
                    if (user == null || custEmail == null || !custEmail.equalsIgnoreCase(user.getEmail())) {
                        log.warn("User {} attempted to access customer {} not owned by them (email fallback enabled)", currentUserId, customerId);
                        throw new uk.gegc.quizmaker.shared.exception.ForbiddenException("Access denied: customer not owned by user");
                    }
                    log.info("User {} accessing customer {} via email fallback (metadata userId missing)", currentUserId, customerId);
                } else {
                    // Metadata-only ownership required
                    log.warn("User {} attempted to access customer {} without proper metadata userId (email fallback disabled)", currentUserId, customerId);
                    throw new uk.gegc.quizmaker.shared.exception.ForbiddenException("Access denied: metadata-only ownership required");
                }
            }
            log.info("User {} accessing own customer {}", currentUserId, customerId);

            CustomerResponse customer = stripeService.retrieveCustomer(customerId);
            return ResponseEntity.ok(customer);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe API error: " + e.getMessage(), e);
        }
    }

    @PostMapping("/create-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<SubscriptionResponse> createSubscription(@Valid @RequestBody CreateSubscriptionRequest request,
                                                                   Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        try {
            // Resolve or create Stripe customer for this user
            String customerId = resolveStripeCustomerId(currentUserId);
            
            log.info("Creating subscription for user {} with customer {} and price {}", 
                    currentUserId, customerId, request.priceId());
            
            SubscriptionResponse subscription = stripeService.createSubscription(customerId, request.priceId());
            return ResponseEntity.ok(subscription);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe API error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Error resolving customer or creating subscription: " + e.getMessage(), e);
        }
    }

    @PostMapping("/update-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<String> updateSubscription(@Valid @RequestBody UpdateSubscriptionRequest request,
                                                     Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        try {
            // Resolve price ID from lookup key via Stripe
            String newPriceId = stripeService.resolvePriceIdByLookupKey(request.newPriceLookupKey());
            
            log.info("Updating subscription {} for user {} to price {}", 
                    request.subscriptionId(), currentUserId, newPriceId);
            
            var subscription = stripeService.updateSubscription(request.subscriptionId(), newPriceId);
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (StripeException e) {
            throw new RuntimeException("Stripe API error: " + e.getMessage(), e);
        }
    }

    @PostMapping("/cancel-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<String> cancelSubscription(@Valid @RequestBody CancelSubscriptionRequest request,
                                                     Authentication authentication) {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        log.info("Cancelling subscription {} for user {}", request.subscriptionId(), currentUserId);
        
        try {
            var subscription = stripeService.cancelSubscription(request.subscriptionId());
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (StripeException e) {
            throw new RuntimeException("Stripe API error: " + e.getMessage(), e);
        }
    }

    private java.util.Optional<UUID> safeParseUuid(String value) {
        try {
            return java.util.Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private UUID resolveAuthenticatedUserId(Authentication authentication) {
        String principal = authentication != null ? authentication.getName() : null;
        if (principal == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        return safeParseUuid(principal)
                .orElseGet(() -> {
                    uk.gegc.quizmaker.features.user.domain.model.User user = userRepository.findByUsername(principal)
                            .or(() -> userRepository.findByEmail(principal))
                            .orElseThrow(() -> new IllegalStateException("Unknown principal"));
                    return user.getId();
                });
    }

    private String resolveStripeCustomerId(UUID userId) throws Exception {
        // Prefer existing Stripe customer ID from the most recent payment
        var page = paymentRepository.findByUserId(
                userId, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        if (!page.isEmpty()) {
            String existing = page.getContent().get(0).getStripeCustomerId();
            if (existing != null && !existing.isBlank()) {
                return existing;
            }
        }

        // Fallback: create a new Stripe customer using user's email
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found for subscription creation"));
        var created = stripeService.createCustomer(userId, user.getEmail());
        return created.id();
    }
}
