-- Bind each new idempotent generation command to the customer tariff that was
-- active when the operation was first claimed. Existing operations remain
-- nullable and follow the documented legacy compatibility path.
ALTER TABLE quiz_generation_operations
    ADD COLUMN billing_tariff_version VARCHAR(128) NULL AFTER canonicalization_version,
    ADD COLUMN billing_base_tokens BIGINT NULL AFTER billing_tariff_version,
    ADD COLUMN billing_tokens_per_thousand_characters DECIMAL(19, 6) NULL AFTER billing_base_tokens,
    ADD CONSTRAINT chk_qgo_tariff_snapshot_complete CHECK (
        (billing_tariff_version IS NULL
            AND billing_base_tokens IS NULL
            AND billing_tokens_per_thousand_characters IS NULL)
        OR
        (billing_tariff_version IS NOT NULL
            AND billing_base_tokens IS NOT NULL
            AND billing_base_tokens >= 0
            AND billing_tokens_per_thousand_characters IS NOT NULL
            AND billing_tokens_per_thousand_characters > 0)
    );
