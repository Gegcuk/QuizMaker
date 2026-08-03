-- Durable economic idempotency marker. A Stripe Checkout Session can settle only once.
CREATE TABLE IF NOT EXISTS checkout_session_settlements (
    stripe_session_id VARCHAR(255) NOT NULL,
    payment_id BINARY(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stripe_session_id),
    CONSTRAINT uq_checkout_session_settlement_payment UNIQUE (payment_id),
    CONSTRAINT fk_checkout_session_settlement_payment
        FOREIGN KEY (payment_id) REFERENCES payments(id)
) ENGINE=InnoDB;

-- Mark historical terminal payments as settled without adding any token credits.
-- CAST avoids enum-literal coercion on databases created before PARTIALLY_REFUNDED existed.
INSERT IGNORE INTO checkout_session_settlements (stripe_session_id, payment_id, created_at)
SELECT stripe_session_id, id, created_at
FROM payments
WHERE CAST(status AS CHAR) IN ('SUCCEEDED', 'REFUNDED', 'PARTIALLY_REFUNDED');
