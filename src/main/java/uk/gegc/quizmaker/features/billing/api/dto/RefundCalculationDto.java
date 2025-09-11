package uk.gegc.quizmaker.features.billing.api.dto;

/**
 * Result of refund calculation showing the policy decision and amounts.
 */
public record RefundCalculationDto(
        /**
         * Whether the refund is allowed based on the policy.
         */
        boolean refundAllowed,
        
        /**
         * The actual refund amount in cents that will be processed.
         */
        long refundAmountCents,
        
        /**
         * The number of tokens that will be deducted.
         */
        long tokensToDeduct,
        
        /**
         * The number of tokens that were originally purchased.
         */
        long originalTokens,
        
        /**
         * The number of tokens that have been spent since purchase.
         */
        long tokensSpent,
        
        /**
         * The number of tokens that remain unspent.
         */
        long tokensUnspent,
        
        /**
         * The reason for the refund decision.
         */
        String reason,
        
        /**
         * The refund policy that was applied.
         */
        String policyApplied
) {}
