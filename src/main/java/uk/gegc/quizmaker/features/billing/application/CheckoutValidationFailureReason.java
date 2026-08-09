package uk.gegc.quizmaker.features.billing.application;

/**
 * Bounded reasons for rejecting a retrieved Stripe Checkout Session.
 */
public enum CheckoutValidationFailureReason {
    MISSING_SESSION,
    MISSING_PAYMENT_SNAPSHOT,
    INCOMPLETE_PAYMENT_SNAPSHOT,
    LINE_ITEM_COUNT,
    PRICE_MISMATCH,
    PACK_MISMATCH,
    USER_MISMATCH,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    QUANTITY_MISMATCH
}
