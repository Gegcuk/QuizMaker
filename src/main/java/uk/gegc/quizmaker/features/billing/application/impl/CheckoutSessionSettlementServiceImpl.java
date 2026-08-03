package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.CheckoutSessionSettlementCommand;
import uk.gegc.quizmaker.features.billing.application.CheckoutSessionSettlementService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.CheckoutSessionSettlement;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProcessedStripeEvent;
import uk.gegc.quizmaker.features.billing.infra.repository.CheckoutSessionSettlementRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;

import java.util.Objects;

/**
 * Serializes settlement by payment row and persists a session-level marker before crediting.
 */
@Service
@RequiredArgsConstructor
public class CheckoutSessionSettlementServiceImpl implements CheckoutSessionSettlementService {

    private static final String CHECKOUT_SESSION_CREDIT_PREFIX = "checkout-session:";

    private final PaymentRepository paymentRepository;
    private final CheckoutSessionSettlementRepository settlementRepository;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final InternalBillingService internalBillingService;

    @Override
    @Transactional
    public SettlementResult settle(CheckoutSessionSettlementCommand command) {
        Payment payment = paymentRepository.findByStripeSessionIdForUpdate(command.stripeSessionId())
                .orElseThrow(() -> invalid("No server-created payment exists for checkout session"));

        if (processedStripeEventRepository.existsByEventId(command.eventId())) {
            return SettlementResult.DUPLICATE_EVENT;
        }

        verifyPaymentBinding(payment, command);
        updateProviderReferences(payment, command);

        if (command.paid()) {
            return settlePaidSession(payment, command);
        }

        return settleUnpaidSession(payment, command);
    }

    private SettlementResult settlePaidSession(Payment payment, CheckoutSessionSettlementCommand command) {
        if (settlementRepository.existsById(command.stripeSessionId())) {
            if (!isAlreadyFinalized(payment)) {
                throw invalid("Checkout session settlement exists without a final payment state");
            }
            markEventProcessed(command.eventId());
            return SettlementResult.ALREADY_SETTLED;
        }

        if (payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            recordSettlement(payment);
            markEventProcessed(command.eventId());
            return SettlementResult.ALREADY_SETTLED;
        }

        recordSettlement(payment);
        internalBillingService.creditPurchase(
                command.userId(),
                command.tokens(),
                CHECKOUT_SESSION_CREDIT_PREFIX + command.stripeSessionId(),
                command.packId().toString(),
                command.sessionMetadata()
        );

        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setSessionMetadata(command.sessionMetadata());
        paymentRepository.save(payment);
        markEventProcessed(command.eventId());
        return SettlementResult.CREDITED;
    }

    private SettlementResult settleUnpaidSession(Payment payment, CheckoutSessionSettlementCommand command) {
        if (isAlreadyFinalized(payment)) {
            markEventProcessed(command.eventId());
            return SettlementResult.ALREADY_SETTLED;
        }

        if (payment.getStatus() == PaymentStatus.PENDING
                && command.unpaidDisposition() == CheckoutSessionSettlementCommand.UnpaidDisposition.MARK_FAILED) {
            payment.setStatus(PaymentStatus.FAILED);
        }

        if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.FAILED) {
            payment.setSessionMetadata(command.sessionMetadata());
            paymentRepository.save(payment);
        }

        markEventProcessed(command.eventId());
        return payment.getStatus() == PaymentStatus.FAILED
                ? SettlementResult.FAILED
                : SettlementResult.PENDING;
    }

    private void verifyPaymentBinding(Payment payment, CheckoutSessionSettlementCommand command) {
        boolean matches = Objects.equals(payment.getUserId(), command.userId())
                && Objects.equals(payment.getPackId(), command.packId())
                && payment.getAmountCents() == command.amountCents()
                && payment.getCreditedTokens() == command.tokens()
                && sameCurrency(payment.getCurrency(), command.currency());

        if (!matches) {
            throw invalid("Checkout session does not match the server-created payment");
        }
    }

    private void updateProviderReferences(Payment payment, CheckoutSessionSettlementCommand command) {
        verifyOrSetProviderReference(payment.getStripePaymentIntentId(), command.stripePaymentIntentId(), "payment intent");
        verifyOrSetProviderReference(payment.getStripeCustomerId(), command.stripeCustomerId(), "customer");

        if (!StringUtils.hasText(payment.getStripePaymentIntentId())) {
            payment.setStripePaymentIntentId(command.stripePaymentIntentId());
        }
        if (!StringUtils.hasText(payment.getStripeCustomerId())) {
            payment.setStripeCustomerId(command.stripeCustomerId());
        }
    }

    private void verifyOrSetProviderReference(String storedReference, String receivedReference, String referenceType) {
        if (StringUtils.hasText(storedReference)
                && StringUtils.hasText(receivedReference)
                && !Objects.equals(storedReference, receivedReference)) {
            throw invalid("Checkout session " + referenceType + " does not match the server-created payment");
        }
    }

    private boolean isAlreadyFinalized(Payment payment) {
        return payment.getStatus() == PaymentStatus.SUCCEEDED
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED;
    }

    private void recordSettlement(Payment payment) {
        CheckoutSessionSettlement settlement = new CheckoutSessionSettlement();
        settlement.setStripeSessionId(payment.getStripeSessionId());
        settlement.setPaymentId(payment.getId());
        settlementRepository.save(settlement);
    }

    private void markEventProcessed(String eventId) {
        ProcessedStripeEvent processedEvent = new ProcessedStripeEvent();
        processedEvent.setEventId(eventId);
        processedStripeEventRepository.save(processedEvent);
    }

    private boolean sameCurrency(String first, String second) {
        return StringUtils.hasText(first) && StringUtils.hasText(second) && first.equalsIgnoreCase(second);
    }

    private InvalidCheckoutSessionException invalid(String message) {
        return new InvalidCheckoutSessionException(message);
    }
}
