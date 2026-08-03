-- Durable command-level idempotency for document, upload, and text quiz generation.
-- The operation stores only metadata and a canonical command hash: never source content.
CREATE TABLE quiz_generation_operations (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    canonicalization_version VARCHAR(16) NOT NULL,
    legacy_key BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(24) NOT NULL,
    source_document_id BINARY(16) NULL,
    job_id BINARY(16) NULL,
    reservation_id BINARY(16) NULL,
    estimated_time_seconds INT NULL,
    source_processing_started_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_qgo_user_type_idempotency_key UNIQUE (user_id, operation_type, idempotency_key),
    CONSTRAINT uq_qgo_job_id UNIQUE (job_id),
    CONSTRAINT uq_qgo_reservation_id UNIQUE (reservation_id),
    INDEX idx_qgo_expires_at (expires_at),
    INDEX idx_qgo_user_created_at (user_id, created_at)
) ENGINE=InnoDB;
