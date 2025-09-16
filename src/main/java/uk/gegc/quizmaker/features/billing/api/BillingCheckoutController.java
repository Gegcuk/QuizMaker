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
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.CheckoutReadService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import com.stripe.model.StripeObject;
import com.stripe.exception.CardException;
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

    @GetMapping("/checkout-sessions/{sessionId}")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<CheckoutSessionStatus> getCheckoutSessionStatus(@PathVariable String sessionId, Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
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

    @GetMapping("/balance")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<BalanceDto> getBalance(Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            
            // Rate limiting: 60 requests per minute per user
            rateLimitService.checkRateLimit("billing-balance", currentUserId.toString(), 60);
            
            log.debug("Retrieving balance for user: {}", currentUserId);
            
            BalanceDto balance = billingService.getBalance(currentUserId);
            
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, max-age=30")
                    .body(balance);
        } catch (Exception e) {
            log.error("Error retrieving balance for current user", e);
            return ResponseEntity.internalServerError().build();
        }
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
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            
            // Rate limiting: 30 requests per minute per user
            rateLimitService.checkRateLimit("billing-transactions", currentUserId.toString(), 30);
            
            log.debug("Retrieving transactions for user: {} with filters - type: {}, source: {}, dateFrom: {}, dateTo: {}", 
                    currentUserId, type, source, dateFrom, dateTo);
            
            Page<TransactionDto> transactions = billingService.listTransactions(
                    currentUserId, pageable, type, source, dateFrom, dateTo);
            
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, max-age=60")
                    .body(transactions);
        } catch (Exception e) {
            log.error("Error retrieving transactions for user: {}", BillingSecurityUtils.getCurrentUserId(), e);
            
            // Handle parameter validation errors as 400 Bad Request
            if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ||
                e instanceof org.springframework.web.bind.MethodArgumentNotValidException ||
                e instanceof org.springframework.validation.BindException ||
                e instanceof org.springframework.web.bind.ServletRequestBindingException) {
                return ResponseEntity.badRequest().build();
            }
            
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/estimate/quiz-generation")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<EstimationDto> estimateQuizGeneration(@Valid @RequestBody GenerateQuizFromDocumentRequest request,
                                                               Authentication authentication) {
        try {
            // Rate limiting: 10 requests per minute per user
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
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

    @PostMapping("/checkout-sessions")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<CheckoutSessionResponse> createCheckoutSession(@Valid @RequestBody CreateCheckoutSessionRequest request,
                                                                         Authentication authentication) {
        try {
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
        } catch (Exception e) {
            log.error("Error creating checkout session: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/create-customer")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request,
                                                           Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            
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
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId,
                                                        Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            // Verify ownership via Stripe customer metadata userId
            var rawCustomer = stripeService.retrieveCustomerRaw(customerId);
            String mdUserId = rawCustomer.getMetadata() != null ? rawCustomer.getMetadata().get("userId") : null;
            if (mdUserId == null || !mdUserId.equalsIgnoreCase(currentUserId.toString())) {
                // Fallback to email-based ownership if metadata not present
                User user = userRepository.findById(currentUserId)
                        .orElse(null);
                String custEmail = rawCustomer.getEmail();
                if (user == null || custEmail == null || !custEmail.equalsIgnoreCase(user.getEmail())) {
                    log.warn("User {} attempted to access customer {} not owned by them", currentUserId, customerId);
                    return ResponseEntity.status(403).build();
                }
            }
            log.info("User {} accessing own customer {}", currentUserId, customerId);

            CustomerResponse customer = stripeService.retrieveCustomer(customerId);
            return ResponseEntity.ok(customer);
        } catch (Exception e) {
            log.error("Error retrieving customer {}: {}", customerId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/create-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<SubscriptionResponse> createSubscription(@Valid @RequestBody CreateSubscriptionRequest request,
                                                                   Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            // Resolve or create Stripe customer for this user
            String customerId = resolveStripeCustomerId(currentUserId);
            
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
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<String> updateSubscription(@Valid @RequestBody UpdateSubscriptionRequest request,
                                                     Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            
            // Resolve price ID from lookup key via Stripe
            String newPriceId = stripeService.resolvePriceIdByLookupKey(request.newPriceLookupKey());
            
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
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<String> cancelSubscription(@Valid @RequestBody CancelSubscriptionRequest request,
                                                     Authentication authentication) {
        try {
            UUID currentUserId = resolveAuthenticatedUserId(authentication);
            
            log.info("Cancelling subscription {} for user {}", request.subscriptionId(), currentUserId);
            
            var subscription = stripeService.cancelSubscription(request.subscriptionId());
            // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
            return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
        } catch (Exception e) {
            log.error("Error cancelling subscription {}: {}", request.subscriptionId(), e.getMessage());
            return ResponseEntity.badRequest().build();
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
