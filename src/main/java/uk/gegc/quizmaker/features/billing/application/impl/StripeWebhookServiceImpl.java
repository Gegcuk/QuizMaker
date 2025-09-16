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
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
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
    private final InternalBillingService internalBillingService;
    private final RefundPolicyService refundPolicyService;
    private final CheckoutValidationService checkoutValidationService;
    private final BillingMetricsService metricsService;
    private final SubscriptionService subscriptionService;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Result process(String payload, String signatureHeader) throws StripeException {
        long startTime = System.currentTimeMillis();
        
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
            
            // Record webhook latency
            long latencyMs = System.currentTimeMillis() - startTime;
            metricsService.recordWebhookLatency(type, latencyMs);
            
            return result;
        } catch (Exception e) {
            // Emit failure metric
            metricsService.incrementWebhookFailed(type);
            loggingContext.logError(log, "Failed to process webhook event: id={} type={}", eventId, type, e);
            throw e; // Re-throw so controller returns 500 and Stripe retries
        } finally {
            WebhookLoggingContext.clearMDC();
        }
    }
    
    private Result routeEvent(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
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
            case "refund.created":
                return handleRefundCreated(event, eventId, loggingContext);
            case "refund.updated":
                return handleRefundUpdated(event, eventId, loggingContext);
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

    private Result handleInvoicePaymentSucceeded(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        loggingContext.logInfo(log, "Payment succeeded for invoice: {}", eventId);
        
        try {
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
        } catch (StripeException e) {
            loggingContext.logError(log, "Stripe API error in invoice payment succeeded processing: {}", e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Error processing invoice payment succeeded: {}", e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        }
    }
    
    private void handleInvoicePaymentSucceeded(Invoice invoice, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        String subscriptionId = invoice.getSubscription();
        
        if (StringUtils.hasText(subscriptionId)) {
            // This is a subscription invoice
            handleSubscriptionInvoicePayment(invoice, subscriptionId, eventId, loggingContext);
        } else {
            // This is a one-time payment invoice (already handled by checkout.session.completed)
            loggingContext.logInfo(log, "One-time payment invoice processed: {}", invoice.getId());
        }
    }
    
    private void handleSubscriptionInvoicePayment(Invoice invoice, String subscriptionId, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        try {
            // Retrieve subscription to get customer and billing period info
            Subscription subscription = stripeService.retrieveSubscription(subscriptionId);
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
            if (!StringUtils.hasText(priceId)) {
                loggingContext.logWarn(log, "No price on subscription {}; skipping credit", subscriptionId);
                return;
            }
            long tokensPerPeriod = subscriptionService.getTokensPerPeriod(subscriptionId, priceId);
            
            // Handle subscription payment success using the subscription service
            boolean credited = subscriptionService.handleSubscriptionPaymentSuccess(
                userId, subscriptionId, periodStart, tokensPerPeriod, eventId);
            
            if (!credited) {
                throw new IllegalStateException("Subscription credit returned false (likely already processed or transient failure)");
            }
            
            loggingContext.logInfo(log, "Successfully credited {} tokens to user {} for subscription {} (period: {})", 
                    tokensPerPeriod, userId, subscriptionId, periodStart);
                    
        } catch (com.stripe.exception.StripeException e) {
            loggingContext.logError(log, "Stripe API error in sub invoice processing for {}: {}", subscriptionId, e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Sub invoice processing failed for {}: {}", subscriptionId, e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        }
    }
    
    private UUID extractUserIdFromCustomer(String customerId) {
        try {
            // In a real implementation, you'd store the user ID in customer metadata
            // For now, we'll try to find it in the customer object
            com.stripe.model.Customer customer = stripeService.retrieveCustomerRaw(customerId);
            String userIdStr = customer.getMetadata().get("userId");
            if (StringUtils.hasText(userIdStr)) {
                return UUID.fromString(userIdStr);
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve customer {}: {}", customerId, e.getMessage());
        }
        return null;
    }
    
    
    private void handleSubscriptionPaymentFailure(Invoice invoice, String subscriptionId, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        try {
            // Retrieve subscription to get customer info
            Subscription subscription = stripeService.retrieveSubscription(subscriptionId);
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
            
        } catch (com.stripe.exception.StripeException e) {
            loggingContext.logError(log, "Stripe API error in subscription payment failure for {}: {}", subscriptionId, e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to handle subscription payment failure for subscription {}: {}", 
                    subscriptionId, e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
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

    private Result handleInvoicePaymentFailed(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        loggingContext.logWarn(log, "Payment failed for invoice: {}", eventId);
        
        try {
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
        } catch (StripeException e) {
            loggingContext.logError(log, "Stripe API error in invoice payment failed processing: {}", e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Error processing invoice payment failed: {}", e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        }
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
            
            loggingContext.logInfo(log, "Subscription {} cancelled for user {}. Subscription blocked immediately.", 
                    subscription.getId(), userId);
                    
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process subscription deletion for subscription {}: {}", 
                    subscription.getId(), e.getMessage(), e);
            throw e; // important: 500 → Stripe retries
        }
    }

    private Result handleRefundCreated(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        loggingContext.logInfo(log, "Refund created: {}", eventId);
        
        // Check for duplicate event
        if (processedStripeEventRepository.existsByEventId(eventId)) {
            loggingContext.logInfo(log, "Duplicate refund event received; id={}", eventId);
            return Result.DUPLICATE;
        }
        
        // Deserialize the refund object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isEmpty() || 
            !(dataObjectDeserializer.getObject().get() instanceof com.stripe.model.Refund refund)) {
            loggingContext.logWarn(log, "Failed to deserialize Refund for event {}", eventId);
            return Result.IGNORED;
        }
        
        try {
            // Only process succeeded refunds
            if (!"succeeded".equalsIgnoreCase(refund.getStatus())) {
                loggingContext.logInfo(log, "Skipping refund {} with status {}", refund.getId(), refund.getStatus());
                return Result.OK;
            }
            
            return processSucceededRefund(refund, eventId, loggingContext);
        } catch (com.stripe.exception.StripeException e) {
            loggingContext.logError(log, "Stripe API error in refund processing for {}: {}", eventId, e.getMessage(), e);
            throw e; // 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Failed processing refund.created {}: {}", eventId, e.getMessage(), e);
            throw e; // 500 → Stripe retries
        }
    }
    
    private Result handleRefundUpdated(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        loggingContext.logInfo(log, "Refund updated: {}", eventId);
        
        // Check for duplicate event
        if (processedStripeEventRepository.existsByEventId(eventId)) {
            loggingContext.logInfo(log, "Duplicate refund update event received; id={}", eventId);
            return Result.DUPLICATE;
        }
        
        // Deserialize the refund object
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isEmpty() || 
            !(dataObjectDeserializer.getObject().get() instanceof com.stripe.model.Refund refund)) {
            loggingContext.logWarn(log, "Failed to deserialize Refund for event {}", eventId);
            return Result.IGNORED;
        }
        
        try {
            String refundStatus = refund.getStatus();
            
            // If refund succeeded, process it (same logic as refund.created)
            if ("succeeded".equalsIgnoreCase(refundStatus)) {
                return processSucceededRefund(refund, eventId, loggingContext);
            }
            // If refund was canceled, we need to restore tokens that were previously deducted
            else if ("canceled".equalsIgnoreCase(refundStatus)) {
                String chargeId = refund.getCharge();
                Charge charge = stripeService.retrieveCharge(chargeId);
                String paymentIntentId = charge.getPaymentIntent();

                var payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
                if (payment.isEmpty()) {
                    loggingContext.logWarn(log, "No payment for canceled refund {} (pi:{})", refund.getId(), paymentIntentId);
                    return Result.OK;
                }

                // Update logging context
                loggingContext.setUserId(payment.get().getUserId());
                loggingContext.setChargeId(chargeId);

                long refundAmountCents = refund.getAmount();
                long originalTokens = payment.get().getCreditedTokens();
                long originalAmountCents = payment.get().getAmountCents();
                
                // Calculate proportional tokens to restore (guard against zero-division)
                long tokensToRestore = 0;
                if (originalAmountCents > 0) {
                    tokensToRestore = (originalTokens * refundAmountCents) / originalAmountCents;
                } else {
                    loggingContext.logWarn(log, "Cannot restore tokens for refund {} - original payment amount is zero", refund.getId());
                }
                
                if (tokensToRestore > 0) {
                    // Stable idempotency key for the restoration
                    String idempotencyKey = String.format("refund-canceled:%s", refund.getId());
                    
                    // Build metadata
                    String metaJson = buildRefundCanceledMetaJson(refund, tokensToRestore);
                    
                    // Restore tokens using PURCHASE transaction (positive amount)
                    internalBillingService.creditPurchase(
                        payment.get().getUserId(), 
                        tokensToRestore, 
                        idempotencyKey, 
                        refund.getId(), 
                        metaJson
                    );
                    
                    loggingContext.logInfo(log, "Restored {} tokens to user {} after refund {} was canceled with idempotency key: {}", 
                            tokensToRestore, payment.get().getUserId(), refund.getId(), idempotencyKey);
                    
                    // Record processed event for observability
                    ProcessedStripeEvent processed = new ProcessedStripeEvent();
                    processed.setEventId(eventId);
                    processedStripeEventRepository.save(processed);
                }
            } else {
                loggingContext.logInfo(log, "Refund {} status changed to {} - no action needed", refund.getId(), refundStatus);
            }
            
            return Result.OK;
        } catch (com.stripe.exception.StripeException e) {
            loggingContext.logError(log, "Stripe API error in refund update processing for {}: {}", eventId, e.getMessage(), e);
            throw e; // 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Failed processing refund.updated {}: {}", eventId, e.getMessage(), e);
            throw e; // 500 → Stripe retries
        }
    }
    
    private Result processSucceededRefund(com.stripe.model.Refund refund, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        try {
            String chargeId = refund.getCharge();
            Charge charge = stripeService.retrieveCharge(chargeId); // to get PI
            String paymentIntentId = charge.getPaymentIntent();

            var payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
            if (payment.isEmpty()) {
                loggingContext.logWarn(log, "No payment for refund {} (pi:{})", refund.getId(), paymentIntentId);
                return Result.OK;
            }

            // Update logging context
            loggingContext.setUserId(payment.get().getUserId());
            loggingContext.setChargeId(chargeId);

            long refundAmountCents = refund.getAmount(); // <-- delta for this refund
            var calculation = refundPolicyService.calculateRefund(payment.get(), refundAmountCents);

            // Use stable refund id for idempotency (no eventId)
            refundPolicyService.processRefund(payment.get(), calculation, refund.getId(), eventId);
            
            loggingContext.logInfo(log, "Processed refund {} for charge {}: {} tokens deducted, amount: {} cents, policy: {}", 
                    refund.getId(), chargeId, calculation.tokensToDeduct(), calculation.refundAmountCents(), 
                    calculation.policyApplied());
            
            // Record processed event for observability
            ProcessedStripeEvent processed = new ProcessedStripeEvent();
            processed.setEventId(eventId);
            processedStripeEventRepository.save(processed);
            
            return Result.OK;
        } catch (com.stripe.exception.StripeException e) {
            loggingContext.logError(log, "Stripe API error in succeeded refund processing for {}: {}", eventId, e.getMessage(), e);
            throw e; // 500 → Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Failed processing succeeded refund {}: {}", eventId, e.getMessage(), e);
            throw e; // 500 → Stripe retries
        }
    }
    
    private String buildRefundCanceledMetaJson(com.stripe.model.Refund refund, long tokensRestored) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("refundId", refund.getId());
            meta.put("chargeId", refund.getCharge());
            meta.put("refundAmountCents", refund.getAmount());
            meta.put("tokensRestored", tokensRestored);
            meta.put("reason", "refund_canceled");
            meta.put("refundStatus", refund.getStatus());
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Failed to build refund canceled metadata JSON: {}", e.getMessage());
            return null;
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

    private Result handleChargeDisputeFundsWithdrawn(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        loggingContext.logWarn(log, "Charge dispute funds withdrawn: {}", eventId);
        
        // Check for duplicate event
        if (processedStripeEventRepository.existsByEventId(eventId)) {
            loggingContext.logInfo(log, "Duplicate dispute funds withdrawn event received; id={}", eventId);
            return Result.DUPLICATE;
        }
        
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
    
    private void handleChargeDisputeFundsWithdrawn(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        try {
            String chargeId = dispute.getCharge();
            
            // Retrieve the charge to get the payment intent ID
            Charge charge = stripeService.retrieveCharge(chargeId);
            String paymentIntentId = charge.getPaymentIntent();
            
            // Find the original payment for this payment intent
            var payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
            if (payment.isEmpty()) {
                loggingContext.logWarn(log, "Could not find original payment for disputed charge {} (payment intent: {})", chargeId, paymentIntentId);
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
            
            // Record processed event for observability
            ProcessedStripeEvent processed = new ProcessedStripeEvent();
            processed.setEventId(eventId);
            processedStripeEventRepository.save(processed);
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute funds withdrawal for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
            throw e; // 500 → Stripe retries
        }
    }

    private Result handleChargeDisputeClosed(Event event, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        loggingContext.logInfo(log, "Charge dispute closed: {}", eventId);
        
        // Check for duplicate event
        if (processedStripeEventRepository.existsByEventId(eventId)) {
            loggingContext.logInfo(log, "Duplicate dispute closed event; id={}", eventId);
            return Result.DUPLICATE;
        }
        
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
    
    private void handleChargeDisputeClosed(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        try {
            String chargeId = dispute.getCharge();
            String status = dispute.getStatus();
            
            loggingContext.logInfo(log, "Dispute closed for charge {}: status={}, amount={}", chargeId, status, dispute.getAmount());
            
            // If we won the dispute, we might want to restore tokens
            if ("won".equalsIgnoreCase(status)) {
                handleDisputeWon(dispute, eventId, loggingContext);
            } else if ("lost".equalsIgnoreCase(status)) {
                // Dispute lost - tokens already deducted when funds were withdrawn
                loggingContext.logInfo(log, "Dispute lost for charge {} - tokens already deducted", chargeId);
            } else {
                // Other dispute statuses (e.g., warning_needs_response, warning_under_review, etc.)
                loggingContext.logInfo(log, "Dispute closed for charge {} with status: {} - no action needed", chargeId, status);
            }
            
            // Record processed event for all dispute closure outcomes (won/lost/other)
            ProcessedStripeEvent processed = new ProcessedStripeEvent();
            processed.setEventId(eventId);
            processedStripeEventRepository.save(processed);
            
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute closure for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
            throw e; // 500 → Stripe retries
        }
    }
    
    private void handleDisputeWon(Dispute dispute, String eventId, WebhookLoggingContext loggingContext) throws StripeException {
        try {
            String chargeId = dispute.getCharge();
            
            // Retrieve the charge to get the payment intent ID
            Charge charge = stripeService.retrieveCharge(chargeId);
            String paymentIntentId = charge.getPaymentIntent();
            
            // Find the original payment for this payment intent
            var payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
            if (payment.isEmpty()) {
                loggingContext.logWarn(log, "No payment for charge {} (pi: {})", chargeId, paymentIntentId);
                return;
            }
            
            // Update logging context
            loggingContext.setUserId(payment.get().getUserId());
            loggingContext.setChargeId(chargeId);
            
            // Restore tokens that were deducted when funds were withdrawn
            long disputeAmountCents = dispute.getAmount();
            long originalTokens = payment.get().getCreditedTokens();
            long originalAmountCents = payment.get().getAmountCents();
            
            // Calculate proportional tokens to restore (guard against zero-division)
            long tokensToRestore = 0;
            if (originalAmountCents > 0) {
                tokensToRestore = (originalTokens * disputeAmountCents) / originalAmountCents;
            } else {
                loggingContext.logWarn(log, "Cannot restore tokens for dispute {} - original payment amount is zero", dispute.getId());
            }
            
            if (tokensToRestore > 0) {
                // Stable idempotency key for the restoration
                String idempotencyKey = String.format("dispute-won:%s", dispute.getId());
                
                // Build metadata
                String metaJson = buildDisputeWonMetaJson(dispute, tokensToRestore);
                
                // Restore tokens using PURCHASE transaction (positive amount)
                internalBillingService.creditPurchase(
                    payment.get().getUserId(), 
                    tokensToRestore, 
                    idempotencyKey, 
                    dispute.getId(), 
                    metaJson
                );
                
                loggingContext.logInfo(log, "Restored {} tokens to user {} after winning dispute for charge {} with idempotency key: {}", 
                        tokensToRestore, payment.get().getUserId(), chargeId, idempotencyKey);
            }
            
        } catch (com.stripe.exception.StripeException e) {
            loggingContext.logError(log, "Stripe API error in dispute win processing for {}: {}", dispute.getId(), e.getMessage(), e);
            throw e; // let webhook 500 so Stripe retries
        } catch (Exception e) {
            loggingContext.logError(log, "Failed to process dispute win for dispute {}: {}", 
                    dispute.getId(), e.getMessage(), e);
            throw e; // let webhook 500 so Stripe retries
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
        internalBillingService.creditPurchase(userId, validationResult.totalTokens(), idempotencyKey, 
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
            if (pi instanceof String id) return id;
            if (pi instanceof com.stripe.model.PaymentIntent p) return p.getId();
            // For expandable fields, try to extract ID safely
            if (pi != null) {
                try {
                    // Try common methods that expandable fields might have
                    var getIdMethod = pi.getClass().getMethod("getId");
                    Object result = getIdMethod.invoke(pi);
                    if (result instanceof String) return (String) result;
                } catch (Exception ignored) {
                    // Fall back to toString if it looks like an ID
                    String str = pi.toString();
                    if (str.startsWith("pi_")) return str;
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private String extractCustomerId(Session session) {
        try {
            Object c = session.getCustomer();
            if (c instanceof String id) return id;
            if (c instanceof com.stripe.model.Customer cust) return cust.getId();
            // For expandable fields, try to extract ID safely
            if (c != null) {
                try {
                    // Try common methods that expandable fields might have
                    var getIdMethod = c.getClass().getMethod("getId");
                    Object result = getIdMethod.invoke(c);
                    if (result instanceof String) return (String) result;
                } catch (Exception ignored) {
                    // Fall back to toString if it looks like an ID
                    String str = c.toString();
                    if (str.startsWith("cus_")) return str;
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    
    private String buildEnhancedPurchaseMetaJson(Session session, CheckoutValidationService.CheckoutValidationResult validationResult) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("sessionId", session.getId());
            meta.put("customerId", extractCustomerId(session));
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

