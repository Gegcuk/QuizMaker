-- Dark command receipts only. No legacy backfill or modification of natural domain uniqueness.
-- IN_PROGRESS is transaction-local; no lease/claim may be independently committed or taken over.
CREATE TABLE attempt_command_receipt (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    user_id BINARY(16) NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_id BINARY(16) NOT NULL,
    operation VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id BINARY(16) NOT NULL,
    expected_version BIGINT NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    acknowledgement_version SMALLINT NULL,
    attempt_id BINARY(16) NULL,
    result_id BINARY(16) NULL,
    state_version BIGINT NULL,
    UNIQUE KEY uq_attempt_receipt_key (user_id, key_hash),
    KEY ix_attempt_receipt_cleanup (attempt_id, id),
    CONSTRAINT fk_receipt_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_receipt_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT chk_receipt_request CHECK (schema_version = 1 AND expected_version >= 0),
    CONSTRAINT chk_receipt_operation CHECK (CAST(operation AS BINARY) IN
        ('START','NAVIGATE_TO_ITEM','SUBMIT_ANSWER','SUBMIT_ATTEMPT')),
    CONSTRAINT chk_receipt_hashes CHECK (REGEXP_LIKE(key_hash,'^[0-9a-f]{64}$','c')
        AND REGEXP_LIKE(input_hash,'^[0-9a-f]{64}$','c')),
    CONSTRAINT chk_receipt_outcome CHECK (
        (CAST(status AS BINARY) = 'IN_PROGRESS' AND acknowledgement_version IS NULL
            AND attempt_id IS NULL AND result_id IS NULL AND state_version IS NULL) OR
        (CAST(status AS BINARY) = 'COMMITTED' AND acknowledgement_version IS NOT NULL
            AND acknowledgement_version = 1 AND attempt_id IS NOT NULL
            AND result_id IS NOT NULL AND state_version IS NOT NULL AND state_version > 0)
    )
) ENGINE=InnoDB;
