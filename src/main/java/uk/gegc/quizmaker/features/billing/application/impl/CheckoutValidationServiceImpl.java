package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.model.LineItem;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationFailureReason;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Validates one retrieved Stripe line item against its server-created payment snapshot. */
@Service
@RequiredArgsConstructor
public class CheckoutValidationServiceImpl implements CheckoutValidationService {

    private final PaymentRepository paymentRepository;
    private final BillingMetricsService metricsService;

    @Override
    public CheckoutValidationResult validateAndResolvePack(Session session, UUID packIdFromMetadata) {
        if (session == null || !StringUtils.hasText(session.getId())) {
            throw invalid(CheckoutValidationFailureReason.MISSING_SESSION, "Checkout session is missing");
        }

        Payment payment = paymentRepository.findByStripeSessionId(session.getId())
                .orElseThrow(() -> invalid(
                        CheckoutValidationFailureReason.MISSING_PAYMENT_SNAPSHOT,
                        "No server-created payment exists for checkout session"
                ));
        requireCompleteSnapshot(payment);

        LineItem lineItem = requireSingleLineItem(session);
        String priceId = requirePriceId(lineItem);
        requireMatchingQuantity(lineItem);
        requireMatchingIdentity(session, payment);
        requireMatchingPack(session.getMetadata(), packIdFromMetadata, payment);
        requireMatchingPrice(session.getMetadata(), priceId, payment);
        requireMatchingAmount(session, lineItem, payment);
        requireMatchingCurrency(session, lineItem, payment);

        return new CheckoutValidationResult(
                payment.getPackId(),
                payment.getStripePriceIdSnapshot(),
                payment.getAmountCents(),
                payment.getCurrency(),
                payment.getCreditedTokens()
        );
    }

    private void requireCompleteSnapshot(Payment payment) {
        boolean complete = payment.getUserId() != null
                && payment.getPackId() != null
                && StringUtils.hasText(payment.getStripePriceIdSnapshot())
                && payment.getAmountCents() > 0
                && StringUtils.hasText(payment.getCurrency())
                && payment.getCreditedTokens() > 0;
        if (!complete) {
            throw invalid(
                    CheckoutValidationFailureReason.INCOMPLETE_PAYMENT_SNAPSHOT,
                    "Checkout payment snapshot is incomplete and requires reconciliation"
            );
        }
    }

    private LineItem requireSingleLineItem(Session session) {
        if (session.getLineItems() == null) {
            throw invalid(
                    CheckoutValidationFailureReason.LINE_ITEM_COUNT,
                    "Checkout session line items were not expanded"
            );
        }
        List<LineItem> lineItems = session.getLineItems().getData();
        if (lineItems == null || lineItems.size() != 1 || lineItems.get(0) == null) {
            throw invalid(
                    CheckoutValidationFailureReason.LINE_ITEM_COUNT,
                    "Checkout session must contain exactly one token-pack line item"
            );
        }
        return lineItems.get(0);
    }

    private String requirePriceId(LineItem lineItem) {
        if (lineItem.getPrice() == null || !StringUtils.hasText(lineItem.getPrice().getId())) {
            throw invalid(
                    CheckoutValidationFailureReason.PRICE_MISMATCH,
                    "Checkout line item is missing its Stripe price"
            );
        }
        return lineItem.getPrice().getId();
    }

    private void requireMatchingQuantity(LineItem lineItem) {
        if (!Objects.equals(lineItem.getQuantity(), 1L)) {
            throw invalid(
                    CheckoutValidationFailureReason.QUANTITY_MISMATCH,
                    "Checkout line-item quantity does not match the original purchase"
            );
        }
    }

    private void requireMatchingIdentity(Session session, Payment payment) {
        UUID clientReferenceUserId = parseOptionalUuid(
                session.getClientReferenceId(),
                CheckoutValidationFailureReason.USER_MISMATCH,
                "Checkout client reference is invalid"
        );
        String metadataUserId = session.getMetadata() != null ? session.getMetadata().get("userId") : null;
        UUID metadataUser = parseOptionalUuid(
                metadataUserId,
                CheckoutValidationFailureReason.USER_MISMATCH,
                "Checkout user metadata is invalid"
        );

        if (clientReferenceUserId == null && metadataUser == null) {
            throw invalid(
                    CheckoutValidationFailureReason.USER_MISMATCH,
                    "Checkout session is missing its user binding"
            );
        }
        if ((clientReferenceUserId != null && !clientReferenceUserId.equals(payment.getUserId()))
                || (metadataUser != null && !metadataUser.equals(payment.getUserId()))) {
            throw invalid(
                    CheckoutValidationFailureReason.USER_MISMATCH,
                    "Checkout session does not match the original purchaser"
            );
        }
    }

    private void requireMatchingPack(Map<String, String> metadata, UUID packIdFromMetadata, Payment payment) {
        UUID metadataPack = packIdFromMetadata;
        if (metadataPack == null && metadata != null && StringUtils.hasText(metadata.get("packId"))) {
            metadataPack = parseOptionalUuid(
                    metadata.get("packId"),
                    CheckoutValidationFailureReason.PACK_MISMATCH,
                    "Checkout pack metadata is invalid"
            );
        }
        if (metadataPack != null && !metadataPack.equals(payment.getPackId())) {
            throw invalid(
                    CheckoutValidationFailureReason.PACK_MISMATCH,
                    "Checkout metadata pack does not match the original purchase"
            );
        }
    }

    private void requireMatchingPrice(Map<String, String> metadata, String lineItemPriceId, Payment payment) {
        if (!lineItemPriceId.equals(payment.getStripePriceIdSnapshot())) {
            throw invalid(
                    CheckoutValidationFailureReason.PRICE_MISMATCH,
                    "Checkout line-item price does not match the original purchase"
            );
        }
        if (metadata != null && StringUtils.hasText(metadata.get("priceId"))
                && !metadata.get("priceId").equals(payment.getStripePriceIdSnapshot())) {
            throw invalid(
                    CheckoutValidationFailureReason.PRICE_MISMATCH,
                    "Checkout metadata price does not match the original purchase"
            );
        }
    }

    private void requireMatchingAmount(Session session, LineItem lineItem, Payment payment) {
        if (!Objects.equals(session.getAmountTotal(), payment.getAmountCents())
                || !Objects.equals(lineItem.getAmountTotal(), payment.getAmountCents())) {
            throw invalid(
                    CheckoutValidationFailureReason.AMOUNT_MISMATCH,
                    "Checkout amount does not match the original purchase"
            );
        }
    }

    private void requireMatchingCurrency(Session session, LineItem lineItem, Payment payment) {
        if (!sameCurrency(session.getCurrency(), payment.getCurrency())
                || !sameCurrency(lineItem.getCurrency(), payment.getCurrency())) {
            throw invalid(
                    CheckoutValidationFailureReason.CURRENCY_MISMATCH,
                    "Checkout currency does not match the original purchase"
            );
        }
    }

    private UUID parseOptionalUuid(
            String value,
            CheckoutValidationFailureReason reason,
            String invalidMessage
    ) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalid(reason, invalidMessage);
        }
    }

    private boolean sameCurrency(String actual, String expected) {
        return StringUtils.hasText(actual)
                && StringUtils.hasText(expected)
                && actual.equalsIgnoreCase(expected);
    }

    private InvalidCheckoutSessionException invalid(CheckoutValidationFailureReason reason, String message) {
        metricsService.recordCheckoutValidationFailure(reason);
        return new InvalidCheckoutSessionException(message);
    }
}
