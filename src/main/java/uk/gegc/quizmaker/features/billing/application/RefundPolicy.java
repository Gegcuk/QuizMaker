package uk.gegc.quizmaker.features.billing.application;

/**
 * Refund policy options for handling token refunds.
 */
public enum RefundPolicy {
    /**
     * Allow negative balances - deduct full refund amount even if it results in negative balance.
     * Best for user experience but requires debt tracking.
     */
    ALLOW_NEGATIVE_BALANCE,
    
    /**
     * Cap refund by unspent token value - only refund tokens that haven't been used.
     * Fair but may result in partial refunds.
     */
    CAP_BY_UNSPENT_TOKENS,
    
    /**
     * Block refunds if tokens have been spent - no refunds allowed after token usage.
     * Simplest but worst user experience.
     */
    BLOCK_IF_TOKENS_SPENT
}
