package uk.gegc.quizmaker.features.billing.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
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
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import com.stripe.model.StripeObject;
import com.stripe.exception.StripeException;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Validated
@Tag(name = "Billing", description = "Token billing, checkout, balance, and transaction management")
@SecurityRequirement(name = "Bearer Authentication")
public class BillingCheckoutController {

    private final BillingService billingService;
    private final CheckoutReadService checkoutReadService;
    private final StripeService stripeService;
    private final EstimationService estimationService;
    private final RateLimitService rateLimitService;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final ProductPackRepository productPackRepository;
    private final BillingProperties billingProperties;
    private final FeatureFlags featureFlags;

    @Operation(
            summary = "Get checkout session status",
            description = "Retrieves the status of a Stripe checkout session. Requires BILLING_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Checkout session status retrieved",
                    content = @Content(schema = @Schema(implementation = CheckoutSessionStatus.class))
            ),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Session not found or billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/checkout-sessions/{sessionId}")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<CheckoutSessionStatus> getCheckoutSessionStatus(
            @Parameter(description = "Stripe checkout session ID", required = true) @PathVariable String sessionId,
            Authentication authentication) {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        CheckoutSessionStatus status = checkoutReadService.getCheckoutSessionStatus(sessionId, currentUserId);
        return ResponseEntity.ok(status);
    }

    @Operation(
            summary = "Get billing configuration",
            description = "Returns available token packs and pricing configuration"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Configuration retrieved",
                    content = @Content(schema = @Schema(implementation = ConfigResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        ConfigResponse config = checkoutReadService.getBillingConfig();
        return ResponseEntity.ok(config);
    }

    @Operation(
            summary = "Get available token packs",
            description = "Returns active token packs, optionally filtered by currency"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Packs retrieved",
                    content = @Content(
                            mediaType = "application/json",
                            array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = PackDto.class))
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/packs")
    public ResponseEntity<List<PackDto>> getPacks(
            @Parameter(description = "Optional currency filter (e.g., usd, gbp)")
            @RequestParam(required = false) String currency
    ) {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(checkoutReadService.getAvailablePacks(currency));
    }

    @Operation(
            summary = "Get token balance",
            description = "Returns the authenticated user's current token balance. Requires BILLING_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Balance retrieved",
                    content = @Content(schema = @Schema(implementation = BalanceDto.class))
            ),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/balance")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<BalanceDto> getBalance(Authentication authentication) {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 60 requests per minute per user
        rateLimitService.checkRateLimit("billing-balance", currentUserId.toString(), 60);

        BalanceDto balance = billingService.getBalance(currentUserId);
        
        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=30")
                .body(balance);
    }

    @Operation(
            summary = "Get transaction history",
            description = "Returns paginated token transaction history with optional filters. Requires BILLING_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Transactions retrieved",
                    content = @Content(schema = @Schema(implementation = Page.class))
            ),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/transactions")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<Page<TransactionDto>> getTransactions(
            @Parameter(description = "Pagination parameters") @PageableDefault(size = 20) Pageable pageable,
            @Parameter(description = "Filter by transaction type") @RequestParam(required = false) TokenTransactionType type,
            @Parameter(description = "Filter by transaction source") @RequestParam(required = false) TokenTransactionSource source,
            @Parameter(description = "Filter from date") @RequestParam(required = false) LocalDateTime dateFrom,
            @Parameter(description = "Filter to date") @RequestParam(required = false) LocalDateTime dateTo,
            Authentication authentication) {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 30 requests per minute per user
        rateLimitService.checkRateLimit("billing-transactions", currentUserId.toString(), 30);

        Page<TransactionDto> transactions = billingService.listTransactions(
                currentUserId, pageable, type, source, dateFrom, dateTo);
        
        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=60")
                .body(transactions);
    }

    @Operation(
            summary = "Estimate quiz generation cost",
            description = "Estimates token cost for quiz generation from a document. Requires BILLING_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Estimation completed",
                    content = @Content(schema = @Schema(implementation = EstimationDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled or document not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (10/min)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/estimate/quiz-generation")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<EstimationDto> estimateQuizGeneration(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Quiz generation request for estimation",
                    required = true
            )
            @Valid @RequestBody GenerateQuizFromDocumentRequest request,
            Authentication authentication) {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        // Rate limiting: 10 requests per minute per user
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        rateLimitService.checkRateLimit("quiz-estimation", currentUserId.toString(), 10);
        
        log.info("Estimating quiz generation for document: {} by user: {}", request.documentId(), currentUserId);
        
        EstimationDto estimation = estimationService.estimateQuizGeneration(request.documentId(), request);
        
        log.info("Estimation completed: {} billing tokens ({} LLM tokens) for user: {}", 
                estimation.estimatedBillingTokens(), estimation.estimatedLlmTokens(), currentUserId);
        
        return ResponseEntity.ok(estimation);
    }

    @Operation(
            summary = "Create checkout session",
            description = "Creates a Stripe checkout session for purchasing token packs. Requires BILLING_WRITE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Checkout session created",
                    content = @Content(schema = @Schema(implementation = CheckoutSessionResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_WRITE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled or pack not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (5/min)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/checkout-sessions")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<CheckoutSessionResponse> createCheckoutSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Checkout session request",
                    required = true
            )
            @Valid @RequestBody CreateCheckoutSessionRequest request,
            Authentication authentication) {
        if (!featureFlags.isBilling()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        
        UUID currentUserId = resolveAuthenticatedUserId(authentication);
        
        // Rate limiting: 5 requests per minute per user
        rateLimitService.checkRateLimit("checkout-session-create", currentUserId.toString(), 5);
        
        log.info("Creating checkout session for user: {} with priceId: {}", currentUserId, request.priceId());
        
        CheckoutSessionResponse session = stripeService.createCheckoutSession(
            currentUserId, 
            request.priceId(), 
            request.packId()
        );

        // Seed a pending payment record so /checkout-sessions/{id} resolves immediately after redirect
        seedPendingPayment(currentUserId, session.sessionId(), request.priceId(), request.packId());
        
        log.info("Checkout session created successfully: {} for user: {}", session.sessionId(), currentUserId);
        
        return ResponseEntity.ok(session);
    }

    @Operation(
            summary = "Create Stripe customer",
            description = "Creates a Stripe customer for the authenticated user. Requires BILLING_WRITE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Customer created",
                    content = @Content(schema = @Schema(implementation = CustomerResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_WRITE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (3/min)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/create-customer")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<CustomerResponse> createCustomer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Customer creation request",
                    required = true
            )
            @Valid @RequestBody CreateCustomerRequest request,
            Authentication authentication) throws StripeException {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);

        // Rate limiting: 3 requests per minute per user (Stripe customer creation is expensive)
        rateLimitService.checkRateLimit("billing-create-customer", currentUserId.toString(), 3);

        log.info("Creating Stripe customer for user {} with email {}", currentUserId, request.email());

        CustomerResponse customer = stripeService.createCustomer(currentUserId, request.email());
        return ResponseEntity.ok(customer);
    }
    @Operation(
            summary = "Get Stripe customer",
            description = "Retrieves Stripe customer details (ownership validated). Requires BILLING_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Customer retrieved",
                    content = @Content(schema = @Schema(implementation = CustomerResponse.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied - not customer owner or missing permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Customer not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/customers/{customerId}")
    @RequirePermission(PermissionName.BILLING_READ)
    public ResponseEntity<CustomerResponse> getCustomer(
            @Parameter(description = "Stripe customer ID", required = true) @PathVariable String customerId,
            Authentication authentication) throws StripeException {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);

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
    }
    @Operation(
            summary = "Create subscription",
            description = "Creates a Stripe subscription for recurring billing. Requires BILLING_WRITE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Subscription created",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_WRITE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Price not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/create-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Subscription creation request",
                    required = true
            )
            @Valid @RequestBody CreateSubscriptionRequest request,
            Authentication authentication) throws StripeException {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);

        // Resolve or create Stripe customer for this user
        String customerId = resolveStripeCustomerId(currentUserId);

        log.info("Creating subscription for user {} with customer {} and price {}",
                currentUserId, customerId, request.priceId());

        SubscriptionResponse subscription = stripeService.createSubscription(customerId, request.priceId());
        return ResponseEntity.ok(subscription);
    }
    @Operation(
            summary = "Update subscription",
            description = "Updates an existing Stripe subscription to a new price. Requires BILLING_WRITE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Subscription updated (returns Stripe subscription JSON)"
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_WRITE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Subscription or price not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/update-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<String> updateSubscription(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Subscription update request",
                    required = true
            )
            @Valid @RequestBody UpdateSubscriptionRequest request,
            Authentication authentication) throws StripeException {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);

        // Resolve price ID from lookup key via Stripe
        String newPriceId = stripeService.resolvePriceIdByLookupKey(request.newPriceLookupKey());

        log.info("Updating subscription {} for user {} to price {}",
                request.subscriptionId(), currentUserId, newPriceId);

        var subscription = stripeService.updateSubscription(request.subscriptionId(), newPriceId);
        // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
        return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
    }
    @Operation(
            summary = "Cancel subscription",
            description = "Cancels an existing Stripe subscription. Requires BILLING_WRITE permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Subscription cancelled (returns Stripe subscription JSON)"
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing BILLING_WRITE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Subscription not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/cancel-subscription")
    @RequirePermission(PermissionName.BILLING_WRITE)
    public ResponseEntity<String> cancelSubscription(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Subscription cancellation request",
                    required = true
            )
            @Valid @RequestBody CancelSubscriptionRequest request,
            Authentication authentication) throws StripeException {
        UUID currentUserId = resolveAuthenticatedUserId(authentication);

        log.info("Cancelling subscription {} for user {}", request.subscriptionId(), currentUserId);

        var subscription = stripeService.cancelSubscription(request.subscriptionId());
        // Use Stripe's PRETTY_PRINT_GSON for consistent formatting like in the example
        return ResponseEntity.ok(StripeObject.PRETTY_PRINT_GSON.toJson(subscription));
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

    private String resolveStripeCustomerId(UUID userId) throws StripeException {
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

    private void seedPendingPayment(UUID userId, String sessionId, String priceId, UUID packId) {
        // Avoid duplicate seed if it somehow exists (e.g., retrying create with same session)
        if (paymentRepository.findByStripeSessionId(sessionId).isPresent()) {
            log.info("Payment already exists for session {}, skipping pending seed", sessionId);
            return;
        }

        long amountCents = 0L;
        long tokens = 0L;
        String currency = "usd"; // safe default; will be overwritten by webhook upsert

        var pack = resolvePack(packId, priceId).orElse(null);
        if (pack != null) {
            amountCents = pack.getPriceCents();
            tokens = pack.getTokens();
            if (StringUtils.hasText(pack.getCurrency())) {
                currency = pack.getCurrency();
            }
        }

        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setStripeSessionId(sessionId);
        payment.setPackId(packId);
        payment.setAmountCents(amountCents);
        payment.setCurrency(currency);
        payment.setCreditedTokens(tokens);
        payment.setRefundedAmountCents(0L);
        payment.setSessionMetadata(buildPendingMetadata(priceId, packId));

        paymentRepository.save(payment);
        log.info("Seeded pending payment for session {} user {} (amountCents={} currency={} tokens={})",
                sessionId, userId, amountCents, currency, tokens);
    }

    private java.util.Optional<ProductPack> resolvePack(UUID packId, String priceId) {
        if (packId != null) {
            var byId = productPackRepository.findById(packId);
            if (byId.isPresent()) return byId;
        }
        if (StringUtils.hasText(priceId)) {
            return productPackRepository.findByStripePriceId(priceId);
        }
        return java.util.Optional.empty();
    }

    private String buildPendingMetadata(String priceId, UUID packId) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("priceId", priceId);
            if (packId != null) {
                meta.put("packId", packId.toString());
            }
            meta.put("status", "PENDING");
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            log.debug("Failed to build pending payment metadata: {}", e.getMessage());
            return null;
        }
    }
}
