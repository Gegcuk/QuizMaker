package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.application.RefundPolicyService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionService;
import uk.gegc.quizmaker.features.billing.application.WebhookLoggingContext;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookServiceImpl implements StripeWebhookService {

    private final StripeProperties stripeProperties;
    private final StripeService stripeService;
    private final BillingService billingService;
    private final RefundPolicyService refundPolicyService;
    private final CheckoutValidationService checkoutValidationService;
    private final BillingMetricsService metricsService;
    private final SubscriptionService subscriptionService;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Result process(String payload, String signatureHeader) {
        String webhookSecret = stripeProperties.getWebhookSecret();
        if (!StringUtils.hasText(webhookSecret)) {
            log.warn("Stripe webhook secret not configured; rejecting request");
            throw new StripeWebhookInvalidSignatureException("Webhook secret not configured");
        }

        final Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw new StripeWebhookInvalidSignatureException("Invalid Stripe signature");
        }

        String eventId = event.getId();
        String type = event.getType();

        // Emit webhook received metric
        metricsService.incrementWebhookReceived(type);
        
        // Create logging context for structured logging
        WebhookLoggingContext loggingContext = WebhookLoggingContext.builder()
                .eventId(eventId)
                .eventType(type)
                .build();
        
        loggingContext.logInfo(log, "Processing Stripe webhook event: id={} type={}", eventId, type);

        // Handle different event types like the Stripe example
        try {
            Result result = routeEvent(event, eventId, loggingContext);
            
            // Emit result metrics
            switch (result) {
                case OK -> metricsService.incrementWebhookOk(type);
                case DUPLICATE -> metricsService.incrementWebhookDuplicate(type);
                case IGNORED -> metricsService.incrementWebhookOk(type); // Treat ignored as OK
            }
            
            return result;
        } catch (Exception e) {
            // Emit failure metric
            metricsService.incrementWebhookFailed(type);
            loggingContext.logError(log, "Failed to process webhook event: id={} type={}", eventId, type, e);
            throw e; // Re-throw to trigger 4xx response (NACK)
        } finally {
            WebhookLoggingContext.clearMDC();
        }
    }
    
    private Result routeEvent(Event event, String eventId, WebhookLoggingContext loggingContext) {
        String type = event.getType();
        
        switch (type) {
            case "checkout.session.completed":
                return handleCheckoutSessionCompleted(event, eventId, loggingContext);
            case "invoice.payment_succeeded":
                return handleInvoicePaymentSucceeded(event, eventId, loggingContext);
            case "invoice.payment_failed":
                return handleInvoicePaymentFailed(event, eventId, loggingContext);
            case "customer.subscription.deleted":
                return handleSubscriptionDeleted(event, eventId, loggingContext);
            case "charge.refunded":
                return handleChargeRefunded(event, eventId, loggingContext);
            case "charge.dispute.created":
                return handleChargeDisputeCreated(event, eventId, loggingContext);
            case "charge.dispute.funds_withdrawn":
                return handleChargeDisputeFundsWithdrawn(event, eventId, loggingContext);
            case "charge.dispute.closed":
                return handleChargeDisputeClosed(event, eventId, loggingContext);
            default:
                loggingContext.logInfo(log, "Ignoring Stripe event id={} type={} (not handled)", eventId, type);
            return Result.IGNORED;
        }

    }

    private Result handleCheckoutSessionCompleted(Event event, String eventId, WebhookLoggingContext loggingContext) {
        // Idempotency at event level
        if (processedStripeEventRepository.existsByEventId(eventId)) {
            loggingContext.logInfo(log, "Duplicate Stripe event received; id={} type={}", eventId, event.getType());
            return Result.DUPLICATE;
        }

        String sessionId = extractSessionId(event, event.toJson());
        if (!StringUtils.hasText(sessionId)) {
            throw new InvalidCheckoutSessionException("Missing session id in event payload");
        }
        
        // Update logging context with session ID
        loggingContext.setSessionId(sessionId);

        // Retrieve session from Stripe (with line_items for pack resolution)
        final Session session;
        try {
            session = stripeService.retrieveSession(sessionId, /*expandLineItems=*/true);
        } catch (StripeException e) {
            loggingContext.logError(log, "Stripe API error while retrieving session {}: {}", sessionId, e.getMessage());
            throw new InvalidCheckoutSessionException("Stripe session retrieval failed");
        }

        UUID userId = extractUserId(session);
        UUID packId = extractPackId(session);
        
        // Validate checkout session and resolve pack(s) with guardrails
        CheckoutValidationService.CheckoutValidationResult validationResult = 
                checkoutValidationService.validateAndResolvePack(session, packId);
        
        // Update logging context with extracted data
        loggingContext.setUserId(userId);
        loggingContext.setPriceId(validationResult.primaryPack().getStripePriceId());

        // Perform DB work transactionally with enhanced idempotency
        upsertPaymentAndCredit(eventId, session, userId, validationResult, loggingContext);
        return Result.OK;
    }

    private Result handleInvoicePaymentSucceeded(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logInfo(log, "Payment succeeded for invoice: {}", eventId);
        
        // Deserialize the nested object inside the event like in the Stripe example
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            log.debug("Successfully deserialized Stripe object: {}", stripeObject.getClass().getSimpleName());
            
            if (stripeObject instanceof Invoice invoice) {
                // Update logging context with subscription ID
                loggingContext.setSubscriptionId(invoice.getSubscription());
                handleInvoicePaymentSucceeded(invoice, eventId, loggingContext);
            }
        } else {
            // Deserialization failed, probably due to an API version mismatch
            loggingContext.logWarn(log, "Failed to deserialize invoice object for event: {}", eventId);
            return Result.IGNORED;
        }
        return Result.OK;
    }
    
    private void handleInvoicePaymentSucceeded(Invoice invoice, String eventId, WebhookLoggingContext loggingContext) {
        String subscriptionId = invoice.getSubscription();
        
        if (StringUtils.hasText(subscriptionId)) {
            // This is a subscription invoice
            handleSubscriptionInvoicePayment(invoice, subscriptionId, eventId, loggingContext);
        } else {
            // This is a one-time payment invoice (already handled by checkout.session.completed)
            loggingContext.logInfo(log, "One-time payment invoice processed: {}", invoice.getId());
        }
    }
    
    private void handleSubscriptionInvoicePayment(Invoice invoice, String subscriptionId, String eventId, WebhookLoggingContext loggingContext) {
        try {
            // Retrieve subscription to get customer and billing period info
            Subscription subscription = Subscription.retrieve(subscriptionId);
            String customerId = subscription.getCustomer();
            
            // Extract user ID from customer metadata
            UUID userId = extractUserIdFromCustomer(customerId);
            if (userId == null) {
                loggingContext.logWarn(log, "Could not extract user ID from customer {} for subscription {}", customerId, subscriptionId);
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(userId);
            loggingContext.setCustomerId(customerId);
            
            // Get billing period info
            long periodStart = subscription.getCurrentPeriodStart();
            
            // Get tokens per period from subscription service
            String priceId = getPriceIdFromSubscription(subscription);
            long tokensPerPeriod = subscriptionService.getTokensPerPeriod(subscriptionId, priceId);
            
            // Handle subscription payment success using the subscription service
            boolean credited = subscriptionService.handleSubscriptionPaymentSuccess(
                userId, subscriptionId, periodStart, tokensPerPeriod, eventId);
            
            if (credited) {
                loggingContext.logInfo(log, "Successfully credited {} tokens to user {} for subscription {} (period: {})", 
                        tokensPerPeriod, userId, subscriptionId, periodStart);
            } else {
                loggingContext.logWarn(log, "Failed to credit tokens for subscription {} - may have been already processed", 
                        subscriptionId);
            }
                    
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process subscription invoice payment for subscription {}: {}", 
                    subscriptionId, e.getMessage(), e);
        }
    }
    
    private UUID extractUserIdFromCustomer(String customerId) {
        try {
            // In a real implementation, you'd store the user ID in customer metadata
            // For now, we'll try to find it in the customer object
            com.stripe.model.Customer customer = com.stripe.model.Customer.retrieve(customerId);
            String userIdStr = customer.getMetadata().get("userId");
            if (StringUtils.hasText(userIdStr)) {
                return UUID.fromString(userIdStr);
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve customer {}: {}", customerId, e.getMessage());
        }
        return null;
    }
    
    
    private void handleSubscriptionPaymentFailure(Invoice invoice, String subscriptionId, String eventId, WebhookLoggingContext loggingContext) {
        try {
            // Retrieve subscription to get customer info
            Subscription subscription = Subscription.retrieve(subscriptionId);
            String customerId = subscription.getCustomer();
            
            // Extract user ID from customer metadata
            UUID userId = extractUserIdFromCustomer(customerId);
            if (userId == null) {
                loggingContext.logWarn(log, "Could not extract user ID from customer {} for failed subscription {}", customerId, subscriptionId);
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(userId);
            loggingContext.setCustomerId(customerId);
            
            // Handle subscription payment failure using subscription service
            String reason = "payment_failed";
            subscriptionService.handleSubscriptionPaymentFailure(userId, subscriptionId, reason);
            
            loggingContext.logWarn(log, "Subscription payment failed for user {} subscription {} - subscription blocked", userId, subscriptionId);
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to handle subscription payment failure for subscription {}: {}", 
                    subscriptionId, e.getMessage(), e);
        }
    }
    
    private String getPriceIdFromSubscription(Subscription subscription) {
        // Extract price ID from subscription items
        // In a real implementation, you'd handle multiple items properly
        if (subscription.getItems() != null && !subscription.getItems().getData().isEmpty()) {
            return subscription.getItems().getData().get(0).getPrice().getId();
        }
        return null;
    }

    private Result handleInvoicePaymentFailed(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logWarn(log, "Payment failed for invoice: {}", eventId);
        
        // Deserialize the invoice object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            
            if (stripeObject instanceof Invoice invoice) {
                String subscriptionId = invoice.getSubscription();
                if (StringUtils.hasText(subscriptionId)) {
                    // This is a subscription payment failure
                    handleSubscriptionPaymentFailure(invoice, subscriptionId, eventId, loggingContext);
                } else {
                    // This is a one-time payment failure
                    loggingContext.logInfo(log, "One-time payment failed for invoice: {}", invoice.getId());
                }
            }
        } else {
            loggingContext.logWarn(log, "Failed to deserialize invoice object for payment failure event: {}", eventId);
        }
        
        return Result.OK;
    }

    private Result handleSubscriptionDeleted(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logInfo(log, "Subscription deleted: {}", eventId);
        
        // Deserialize the subscription object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            
            if (stripeObject instanceof Subscription subscription) {
                loggingContext.setSubscriptionId(subscription.getId());
                handleSubscriptionDeleted(subscription, eventId, loggingContext);
            }
        } else {
            loggingContext.logWarn(log, "Failed to deserialize subscription object for event: {}", eventId);
        }
        
        return Result.OK;
    }
    
    private void handleSubscriptionDeleted(Subscription subscription, String eventId, WebhookLoggingContext loggingContext) {
        try {
            String customerId = subscription.getCustomer();
            UUID userId = extractUserIdFromCustomer(customerId);
            
            if (userId == null) {
                loggingContext.logWarn(log, "Could not extract user ID from customer {} for deleted subscription {}", customerId, subscription.getId());
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(userId);
            loggingContext.setCustomerId(customerId);
            
            // Handle subscription deletion using subscription service
            String reason = "subscription_deleted";
            subscriptionService.handleSubscriptionDeleted(userId, subscription.getId(), reason);
            
            loggingContext.logInfo(log, "Subscription {} cancelled for user {}. Access continues until period end: {}", 
                    subscription.getId(), userId, subscription.getCurrentPeriodEnd());
                    
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process subscription deletion for subscription {}: {}", 
                    subscription.getId(), e.getMessage(), e);
        }
    }

    private Result handleChargeRefunded(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logInfo(log, "Charge refunded: {}", eventId);
        
        // Deserialize the charge object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            
            if (stripeObject instanceof Charge charge) {
                loggingContext.setChargeId(charge.getId());
                handleChargeRefunded(charge, eventId, loggingContext);
            }
        } else {
            loggingContext.logWarn(log, "Failed to deserialize charge object for event: {}", eventId);
        }
        
        return Result.OK;
    }
    
    private void handleChargeRefunded(Charge charge, String eventId, WebhookLoggingContext loggingContext) {
        try {
            String customerId = charge.getCustomer();
            if (!StringUtils.hasText(customerId)) {
                loggingContext.logWarn(log, "Charge {} has no customer ID, cannot process refund", charge.getId());
                return;
            }
            
            UUID userId = extractUserIdFromCustomer(customerId);
            if (userId == null) {
                loggingContext.logWarn(log, "Could not extract user ID from customer {} for refunded charge {}", customerId, charge.getId());
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(userId);
            loggingContext.setCustomerId(customerId);
            
            // Find the original payment for this charge
            var payment = paymentRepository.findByStripePaymentIntentId(charge.getPaymentIntent());
            if (payment.isEmpty()) {
                loggingContext.logWarn(log, "Could not find original payment for charge {}", charge.getId());
                return;
            }
            
            long refundAmountCents = charge.getAmountRefunded();
            
            // Calculate refund using the policy service
            var calculation = refundPolicyService.calculateRefund(payment.get(), refundAmountCents);
            
            // Process the refund according to the policy
            refundPolicyService.processRefund(payment.get(), calculation, charge.getId(), eventId);
            
            loggingContext.logInfo(log, "Processed refund for charge {}: {} tokens deducted, amount: {} cents, policy: {}", 
                    charge.getId(), calculation.tokensToDeduct(), calculation.refundAmountCents(), 
                    calculation.policyApplied());
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process charge refund for charge {}: {}", 
                    charge.getId(), e.getMessage(), e);
        }
    }
    

    private Result handleChargeDisputeCreated(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logInfo(log, "Charge dispute created: {}", eventId);
        
        // Deserialize the dispute object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            
            if (stripeObject instanceof Dispute dispute) {
                loggingContext.setDisputeId(dispute.getId());
                handleChargeDisputeCreated(dispute, eventId, loggingContext);
            }
        } else {
            loggingContext.logWarn(log, "Failed to deserialize dispute object for event: {}", eventId);
        }
        
        return Result.OK;
    }
    
    private void handleChargeDisputeCreated(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) {
        try {
            String chargeId = dispute.getCharge();
            loggingContext.logWarn(log, "Dispute created for charge {}: amount={}, reason={}, status={}", 
                    chargeId, dispute.getAmount(), dispute.getReason(), dispute.getStatus());
            
            // For now, just log the dispute creation
            // In a real implementation, you might:
            // - Send notification to customer support
            // - Flag the account for review
            // - Prepare evidence for dispute response
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute creation for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
        }
    }

    private Result handleChargeDisputeFundsWithdrawn(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logWarn(log, "Charge dispute funds withdrawn: {}", eventId);
        
        // Deserialize the dispute object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            
            if (stripeObject instanceof Dispute dispute) {
                loggingContext.setDisputeId(dispute.getId());
                handleChargeDisputeFundsWithdrawn(dispute, eventId, loggingContext);
            }
        } else {
            loggingContext.logWarn(log, "Failed to deserialize dispute object for event: {}", eventId);
        }
        
        return Result.OK;
    }
    
    private void handleChargeDisputeFundsWithdrawn(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) {
        try {
            String chargeId = dispute.getCharge();
            
            // Find the original payment for this charge
            var payment = paymentRepository.findByStripePaymentIntentId(chargeId);
            if (payment.isEmpty()) {
                loggingContext.logWarn(log, "Could not find original payment for disputed charge {}", chargeId);
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(payment.get().getUserId());
            loggingContext.setChargeId(chargeId);
            
            long disputeAmountCents = dispute.getAmount();
            
            // Calculate refund using the policy service (treat as refund)
            var calculation = refundPolicyService.calculateRefund(payment.get(), disputeAmountCents);
            
            // Process the dispute as a refund according to the policy
            refundPolicyService.processRefund(payment.get(), calculation, dispute.getId(), eventId);
            
            loggingContext.logWarn(log, "Processed dispute funds withdrawal for charge {}: {} tokens deducted, amount: {} cents, policy: {}", 
                    chargeId, calculation.tokensToDeduct(), calculation.refundAmountCents(), 
                    calculation.policyApplied());
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute funds withdrawal for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
        }
    }

    private Result handleChargeDisputeClosed(Event event, String eventId, WebhookLoggingContext loggingContext) {
        loggingContext.logInfo(log, "Charge dispute closed: {}", eventId);
        
        // Deserialize the dispute object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            
            if (stripeObject instanceof Dispute dispute) {
                loggingContext.setDisputeId(dispute.getId());
                handleChargeDisputeClosed(dispute, eventId, loggingContext);
            }
        } else {
            loggingContext.logWarn(log, "Failed to deserialize dispute object for event: {}", eventId);
        }
        
        return Result.OK;
    }
    
    private void handleChargeDisputeClosed(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) {
        try {
            String chargeId = dispute.getCharge();
            String status = dispute.getStatus();
            
            loggingContext.logInfo(log, "Dispute closed for charge {}: status={}, amount={}", chargeId, status, dispute.getAmount());
            
            // If we won the dispute, we might want to restore tokens
            if ("won".equals(status)) {
                handleDisputeWon(dispute, eventId, loggingContext);
            } else if ("lost".equals(status)) {
                // Dispute lost - tokens already deducted when funds were withdrawn
                loggingContext.logInfo(log, "Dispute lost for charge {} - tokens already deducted", chargeId);
            }
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute closure for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
        }
    }
    
    private void handleDisputeWon(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) {
        try {
            String chargeId = dispute.getCharge();
            
            // Find the original payment for this charge
            var payment = paymentRepository.findByStripePaymentIntentId(chargeId);
            if (payment.isEmpty()) {
                loggingContext.logWarn(log, "Could not find original payment for won dispute charge {}", chargeId);
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(payment.get().getUserId());
            loggingContext.setChargeId(chargeId);
            
            // Restore tokens that were deducted when funds were withdrawn
            long disputeAmountCents = dispute.getAmount();
            long originalTokens = payment.get().getCreditedTokens();
            long originalAmountCents = payment.get().getAmountCents();
            
            // Calculate proportional tokens to restore
            long tokensToRestore = (originalTokens * disputeAmountCents) / originalAmountCents;
            
            if (tokensToRestore > 0) {
                // Enhanced idempotency key for the restoration
                String idempotencyKey = String.format("dispute-won:%s:%s:%s", eventId, dispute.getId(), chargeId);
                
                // Build metadata
                String metaJson = buildDisputeWonMetaJson(dispute, tokensToRestore);
                
                // Restore tokens using PURCHASE transaction (positive amount)
                billingService.creditPurchase(
                    payment.get().getUserId(), 
                    tokensToRestore, 
                    idempotencyKey, 
                    dispute.getId(), 
                    metaJson
                );
                
                loggingContext.logInfo(log, "Restored {} tokens to user {} after winning dispute for charge {} with idempotency key: {}", 
                        tokensToRestore, payment.get().getUserId(), chargeId, idempotencyKey);
            }
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute win for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
        }
    }
    
    private String buildDisputeWonMetaJson(Dispute dispute, long tokensRestored) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("disputeId", dispute.getId());
            meta.put("chargeId", dispute.getCharge());
            meta.put("disputeAmountCents", dispute.getAmount());
            meta.put("tokensRestored", tokensRestored);
            meta.put("reason", "dispute_won");
            meta.put("disputeStatus", dispute.getStatus());
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Failed to build dispute won metadata JSON: {}", e.getMessage());
            return null;
        }
    }

    @Transactional
    protected void upsertPaymentAndCredit(String eventId, Session session, UUID userId, 
            CheckoutValidationService.CheckoutValidationResult validationResult, WebhookLoggingContext loggingContext) {
        
        // Upsert Payment → SUCCEEDED with enhanced auditability
        Payment payment = paymentRepository.findByStripeSessionId(session.getId())
                .orElseGet(Payment::new);
        payment.setUserId(userId);
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setStripeSessionId(session.getId());
        payment.setStripePaymentIntentId(extractPaymentIntentId(session));
        payment.setPackId(validationResult.primaryPack().getId());
        
        // Persist unit price & currency alongside Payment for auditability
        payment.setAmountCents(validationResult.totalAmountCents());
        payment.setCurrency(validationResult.currency());
        payment.setCreditedTokens(validationResult.totalTokens());
        
        payment.setStripeCustomerId(extractCustomerId(session));
        
        // Enhanced metadata with validation details
        String enhancedMetadata = buildEnhancedPurchaseMetaJson(session, validationResult);
        payment.setSessionMetadata(enhancedMetadata);
        
        paymentRepository.save(payment);

        // Enhanced idempotency key: eventId:sessionId for per-event uniqueness
        String idempotencyKey = String.format("checkout:%s:%s", eventId, session.getId());
        
        loggingContext.logInfo(log, "Crediting {} tokens to user {} for session {} with idempotency key: {} ({} pack(s))", 
                validationResult.totalTokens(), userId, session.getId(), idempotencyKey, validationResult.getPackCount());
        
        // Credit total tokens from all packs
        billingService.creditPurchase(userId, validationResult.totalTokens(), idempotencyKey, 
                validationResult.primaryPack().getId().toString(), enhancedMetadata);

        // Mark event processed after successful credit (DB work committed)
        ProcessedStripeEvent processed = new ProcessedStripeEvent();
        processed.setEventId(eventId);
        processedStripeEventRepository.save(processed);
        
        loggingContext.logInfo(log, "Successfully processed checkout session {} for user {} with {} tokens from {} pack(s)", 
                session.getId(), userId, validationResult.totalTokens(), validationResult.getPackCount());
    }

    private String extractSessionId(Event event, String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String id = root.path("data").path("object").path("id").asText(null);
            if (StringUtils.hasText(id)) return id;
        } catch (Exception ignored) {}
        try {
            var deser = event.getDataObjectDeserializer();
            var opt = deser.getObject();
            if (opt.isPresent() && opt.get() instanceof Session s) {
                return s.getId();
            }
        } catch (Exception ignored) {}
        try {
            JsonNode root = objectMapper.readTree(event.toJson());
            return root.path("data").path("object").path("id").asText(null);
        } catch (Exception ignored) {}
        return null;
    }

    private UUID extractUserId(Session session) {
        String userIdStr = null;
        if (session.getClientReferenceId() != null) userIdStr = session.getClientReferenceId();
        Map<String, String> md = session.getMetadata();
        if (!StringUtils.hasText(userIdStr) && md != null) {
            userIdStr = md.get("userId");
        }
        if (!StringUtils.hasText(userIdStr)) {
            throw new InvalidCheckoutSessionException("userId metadata missing");
        }
        try { return UUID.fromString(userIdStr); } catch (IllegalArgumentException e) {
            throw new InvalidCheckoutSessionException("Invalid userId in metadata");
        }
    }

    private UUID extractPackId(Session session) {
        Map<String, String> md = session.getMetadata();
        if (md != null) {
            String packIdStr = md.get("packId");
            if (StringUtils.hasText(packIdStr)) {
                try { return UUID.fromString(packIdStr); } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }


    private String extractPaymentIntentId(Session session) {
        try {
            Object pi = session.getPaymentIntent();
            return pi != null ? pi.toString() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private String extractCustomerId(Session session) {
        try {
            Object c = session.getCustomer();
            return c != null ? c.toString() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    
    private String buildEnhancedPurchaseMetaJson(Session session, CheckoutValidationService.CheckoutValidationResult validationResult) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("sessionId", session.getId());
            meta.put("customerId", session.getCustomer());
            meta.put("paymentIntentId", extractPaymentIntentId(session));
            meta.put("sessionMetadata", session.getMetadata());
            
            // Primary pack details
            ProductPack primaryPack = validationResult.primaryPack();
            meta.put("primaryPack", java.util.Map.of(
                    "id", primaryPack.getId().toString(),
                    "name", primaryPack.getName(),
                    "stripePriceId", primaryPack.getStripePriceId(),
                    "amountCents", primaryPack.getPriceCents(),
                    "currency", primaryPack.getCurrency(),
                    "tokens", primaryPack.getTokens()
            ));
            
            // Additional packs (if any)
            if (validationResult.additionalPacks() != null && !validationResult.additionalPacks().isEmpty()) {
                java.util.List<java.util.Map<String, Object>> additionalPacks = new java.util.ArrayList<>();
                for (ProductPack pack : validationResult.additionalPacks()) {
                    additionalPacks.add(java.util.Map.of(
                            "id", pack.getId().toString(),
                            "name", pack.getName(),
                            "stripePriceId", pack.getStripePriceId(),
                            "amountCents", pack.getPriceCents(),
                            "currency", pack.getCurrency(),
                            "tokens", pack.getTokens()
                    ));
                }
                meta.put("additionalPacks", additionalPacks);
            }
            
            // Totals and validation details
            meta.put("totals", java.util.Map.of(
                    "totalAmountCents", validationResult.totalAmountCents(),
                    "totalTokens", validationResult.totalTokens(),
                    "currency", validationResult.currency(),
                    "packCount", validationResult.getPackCount(),
                    "hasMultipleLineItems", validationResult.hasMultipleLineItems()
            ));
            
            // Session details for auditability
            meta.put("sessionDetails", java.util.Map.of(
                    "currency", session.getCurrency(),
                    "amountTotal", session.getAmountTotal(),
                    "mode", session.getMode(),
                    "paymentStatus", session.getPaymentStatus()
            ));
            
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Failed to build enhanced purchase metadata JSON: {}", e.getMessage());
            return null;
        }
    }
}

