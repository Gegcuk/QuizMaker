-- Durable, short-lived claims prevent duplicate Stripe subscription mutations.
-- Client idempotency keys are stored only as SHA-256 hashes.
CREATE TABLE subscription_mutation_operations (
    id BINARY(16) NOT NULL,
    subscription_status_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    subscription_id VARCHAR(255) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    target_price_id VARCHAR(255) NULL,
    idempotency_key_hash CHAR(64) NULL,
    request_hash CHAR(64) NOT NULL,
    stripe_idempotency_key VARCHAR(96) NOT NULL,
    state VARCHAR(16) NOT NULL,
    lease_expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_smo_subscription_status FOREIGN KEY (subscription_status_id)
        REFERENCES subscription_status(id) ON DELETE CASCADE,
    CONSTRAINT uq_smo_user_idempotency_key_hash UNIQUE (user_id, idempotency_key_hash),
    CONSTRAINT uq_smo_stripe_idempotency_key UNIQUE (stripe_idempotency_key),
    CONSTRAINT chk_smo_operation_type CHECK (operation_type IN ('UPDATE', 'CANCEL')),
    CONSTRAINT chk_smo_state CHECK (state IN ('IN_PROGRESS', 'RETRYABLE', 'SUCCEEDED')),
    CONSTRAINT chk_smo_target_price CHECK (
        (operation_type = 'UPDATE' AND target_price_id IS NOT NULL)
        OR (operation_type = 'CANCEL' AND target_price_id IS NULL)
    ),
    INDEX idx_smo_subscription_state_created (user_id, subscription_id, state, created_at)
) ENGINE=InnoDB;
