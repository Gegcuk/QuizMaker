package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.RefundPolicy;
import uk.gegc.quizmaker.features.billing.application.RefundPolicyService;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;


@Slf4j
@Service
@RequiredArgsConstructor
public class RefundPolicyServiceImpl implements RefundPolicyService {

    private final InternalBillingService internalBillingService;
    private final PaymentRepository paymentRepository;
    private final TokenTransactionRepository transactionRepository;
    
    @Value("${billing.refund-policy:ALLOW_NEGATIVE_BALANCE}")
    private RefundPolicy refundPolicy;

    @Override
    public RefundCalculationDto calculateRefund(Payment payment, long refundAmountCents) {
        long originalTokens = payment.getCreditedTokens();
        long originalAmountCents = payment.getAmountCents();
        
        // Calculate tokens spent since this payment
        long tokensSpent = calculateTokensSpentSincePayment(payment);
        long tokensUnspent = Math.max(0, originalTokens - tokensSpent);
        
        // Calculate proportional refund based on amount
        long proportionalTokens = (originalTokens * refundAmountCents) / originalAmountCents;
        
        return switch (refundPolicy) {
            case ALLOW_NEGATIVE_BALANCE -> {
                // Allow full refund even if it results in negative balance
                yield new RefundCalculationDto(
                    true,
                    refundAmountCents,
                    proportionalTokens,
                    originalTokens,
                    tokensSpent,
                    tokensUnspent,
                    "Full refund allowed - negative balance permitted",
                    "ALLOW_NEGATIVE_BALANCE"
                );
            }
            case CAP_BY_UNSPENT_TOKENS -> {
                // Only refund tokens that haven't been spent
                long cappedTokens = Math.min(proportionalTokens, tokensUnspent);
                long cappedAmountCents = (cappedTokens * originalAmountCents) / originalTokens;
                
                yield new RefundCalculationDto(
                    cappedTokens > 0,
                    cappedAmountCents,
                    cappedTokens,
                    originalTokens,
                    tokensSpent,
                    tokensUnspent,
                    cappedTokens == proportionalTokens 
                        ? "Full refund allowed - all tokens unspent"
                        : "Partial refund - capped by unspent tokens",
                    "CAP_BY_UNSPENT_TOKENS"
                );
            }
            case BLOCK_IF_TOKENS_SPENT -> {
                // Block refund if any tokens have been spent
                boolean allowed = tokensSpent == 0;
                yield new RefundCalculationDto(
                    allowed,
                    allowed ? refundAmountCents : 0,
                    allowed ? proportionalTokens : 0,
                    originalTokens,
                    tokensSpent,
                    tokensUnspent,
                    allowed 
                        ? "Refund allowed - no tokens spent"
                        : "Refund blocked - tokens have been spent",
                    "BLOCK_IF_TOKENS_SPENT"
                );
            }
        };
    }

    @Override
    @Transactional
    public void processRefund(Payment payment, RefundCalculationDto calculation, String refundId, String eventId) {
        if (!calculation.refundAllowed()) {
            log.warn("Refund not allowed for payment {}: {}", payment.getId(), calculation.reason());
            return;
        }

        if (calculation.tokensToDeduct() <= 0) {
            log.info("No tokens to deduct for payment {} refund", payment.getId());
            return;
        }

        // Create stable idempotency key (no eventId to prevent double-deduction across webhook deliveries)
        String idempotencyKey = String.format("refund:%s", refundId);
        
        // Build metadata
        String metaJson = buildRefundMetaJson(payment, calculation, refundId);
        
        // Deduct tokens using ADJUSTMENT transaction
        internalBillingService.deductTokens(
            payment.getUserId(), 
            calculation.tokensToDeduct(), 
            idempotencyKey, 
            refundId, 
            metaJson
        );
        
        // Update cumulative refunded amount and payment status
        long newRefunded = payment.getRefundedAmountCents() + calculation.refundAmountCents();
        payment.setRefundedAmountCents(newRefunded);
        payment.setStatus(newRefunded >= payment.getAmountCents() 
            ? PaymentStatus.REFUNDED 
            : PaymentStatus.PARTIALLY_REFUNDED);
        paymentRepository.save(payment);
        
        log.info("Processed refund for payment {}: deducted {} tokens, amount: {} cents", 
                payment.getId(), calculation.tokensToDeduct(), calculation.refundAmountCents());
    }

    @Override
    public RefundPolicy getRefundPolicy() {
        return refundPolicy;
    }

    private long calculateTokensSpentSincePayment(Payment payment) {
        // Find all COMMIT transactions for this user after the payment date
        // This is a simplified calculation - in reality you might want more sophisticated tracking
        return transactionRepository.findByUserIdAndTypeAndCreatedAtAfter(
            payment.getUserId(), 
            TokenTransactionType.COMMIT, 
            payment.getCreatedAt()
        ).stream()
        .mapToLong(tx -> tx.getAmountTokens())
        .sum();
    }

    private String buildRefundMetaJson(Payment payment, RefundCalculationDto calculation, String refundId) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("paymentId", payment.getId().toString());
            meta.put("refundId", refundId);
            meta.put("originalAmountCents", payment.getAmountCents());
            meta.put("refundAmountCents", calculation.refundAmountCents());
            meta.put("originalTokens", calculation.originalTokens());
            meta.put("tokensDeducted", calculation.tokensToDeduct());
            meta.put("tokensSpent", calculation.tokensSpent());
            meta.put("tokensUnspent", calculation.tokensUnspent());
            meta.put("policyApplied", calculation.policyApplied());
            meta.put("reason", calculation.reason());
            meta.put("stripeSessionId", payment.getStripeSessionId());
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Failed to build refund metadata JSON: {}", e.getMessage());
            return null;
        }
    }
}
