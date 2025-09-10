package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookServiceImpl implements StripeWebhookService {

    private final StripeProperties stripeProperties;
    private final StripeService stripeService;
    private final BillingService billingService;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final ProductPackRepository productPackRepository;
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

        // Handle different event types like the Stripe example
        switch (type) {
            case "checkout.session.completed":
                return handleCheckoutSessionCompleted(event, eventId);
            case "invoice.payment_succeeded":
                return handleInvoicePaymentSucceeded(event, eventId);
            case "invoice.payment_failed":
                return handleInvoicePaymentFailed(event, eventId);
            case "customer.subscription.deleted":
                return handleSubscriptionDeleted(event, eventId);
            default:
                log.info("Ignoring Stripe event id={} type={} (not handled)", eventId, type);
                return Result.IGNORED;
        }

    }

    private Result handleCheckoutSessionCompleted(Event event, String eventId) {
        // Idempotency at event level
        if (processedStripeEventRepository.existsByEventId(eventId)) {
            log.info("Duplicate Stripe event received; id={} type={}", eventId, event.getType());
            return Result.DUPLICATE;
        }

        String sessionId = extractSessionId(event, event.toJson());
        if (!StringUtils.hasText(sessionId)) {
            throw new InvalidCheckoutSessionException("Missing session id in event payload");
        }

        // Retrieve session from Stripe (with line_items for pack resolution)
        final Session session;
        try {
            session = stripeService.retrieveSession(sessionId, /*expandLineItems=*/true);
        } catch (StripeException e) {
            log.error("Stripe API error while retrieving session {}: {}", sessionId, e.getMessage());
            throw new InvalidCheckoutSessionException("Stripe session retrieval failed");
        }

        UUID userId = extractUserId(session);
        UUID packId = extractPackId(session);
        ProductPack pack = resolvePack(session, packId);

        // Perform DB work transactionally
        upsertPaymentAndCredit(eventId, session, userId, pack);
        return Result.OK;
    }

    private Result handleInvoicePaymentSucceeded(Event event, String eventId) {
        log.info("Payment succeeded for invoice: {}", eventId);
        
        // Deserialize the nested object inside the event like in the Stripe example
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            StripeObject stripeObject = dataObjectDeserializer.getObject().get();
            log.debug("Successfully deserialized Stripe object: {}", stripeObject.getClass().getSimpleName());
            // Handle subscription activation and payment method setting like in the Stripe example
            // This could be extended to handle subscription-related payments
        } else {
            // Deserialization failed, probably due to an API version mismatch
            log.warn("Failed to deserialize invoice object for event: {}", eventId);
            return Result.IGNORED;
        }
        return Result.OK;
    }

    private Result handleInvoicePaymentFailed(Event event, String eventId) {
        log.warn("Payment failed for invoice: {}", eventId);
        // Handle payment failure notifications
        // Could send notifications to users about failed payments
        return Result.OK;
    }

    private Result handleSubscriptionDeleted(Event event, String eventId) {
        log.info("Subscription deleted: {}", eventId);
        // Handle subscription cancellation
        // Could update user access levels or send notifications
        return Result.OK;
    }

    @Transactional
    protected void upsertPaymentAndCredit(String eventId, Session session, UUID userId, ProductPack pack) {
        // Upsert Payment → SUCCEEDED
        Payment payment = paymentRepository.findByStripeSessionId(session.getId())
                .orElseGet(Payment::new);
        payment.setUserId(userId);
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setStripeSessionId(session.getId());
        payment.setStripePaymentIntentId(extractPaymentIntentId(session));
        payment.setPackId(pack.getId());
        payment.setAmountCents(pack.getPriceCents());
        payment.setCurrency(pack.getCurrency());
        payment.setCreditedTokens(pack.getTokens());
        payment.setStripeCustomerId(extractCustomerId(session));
        payment.setSessionMetadata(writeMetadataJson(session.getMetadata()));
        paymentRepository.save(payment);

        // Credit tokens idempotently using session id as the idempotency key
        String metaJson = buildPurchaseMetaJson(session, pack);
        billingService.creditPurchase(userId, pack.getTokens(), session.getId(), pack.getId().toString(), metaJson);

        // Mark event processed after successful credit
        ProcessedStripeEvent processed = new ProcessedStripeEvent();
        processed.setEventId(eventId);
        processedStripeEventRepository.save(processed);
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

    private ProductPack resolvePack(Session session, UUID packIdFromMetadata) {
        if (packIdFromMetadata != null) {
            return productPackRepository.findById(packIdFromMetadata)
                    .orElseThrow(() -> new InvalidCheckoutSessionException("Pack referenced in metadata not found"));
        }
        // Fallback: resolve via Price ID from first line item (if expanded)
        try {
            var lineItems = session.getLineItems();
            if (lineItems != null && !lineItems.getData().isEmpty()) {
                var first = lineItems.getData().get(0);
                String priceId = null;
                try {
                    var price = first.getPrice();
                    if (price != null) priceId = price.getId();
                } catch (Throwable ignored) {}
                if (StringUtils.hasText(priceId)) {
                    Optional<ProductPack> byPrice = productPackRepository.findByStripePriceId(priceId);
                    if (byPrice.isPresent()) return byPrice.get();
                }
            }
        } catch (Throwable ignored) {}
        throw new InvalidCheckoutSessionException("Unable to resolve pack from session");
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

    private String writeMetadataJson(Map<String, String> meta) {
        try {
            return meta == null || meta.isEmpty() ? null : objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPurchaseMetaJson(Session session, ProductPack pack) {
        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("sessionId", session.getId());
            m.put("paymentIntentId", extractPaymentIntentId(session));
            m.put("customerId", extractCustomerId(session));
            m.put("packId", pack.getId().toString());
            m.put("priceCents", pack.getPriceCents());
            m.put("currency", pack.getCurrency());
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }
}

