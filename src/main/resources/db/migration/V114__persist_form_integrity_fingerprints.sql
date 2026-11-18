-- Existing target forms remain explicitly unverified. Never bootstrap history on a read/replay.
-- The application inserts both fields only with a new complete form/order in one transaction.
-- Legacy attempts are untouched. After target writes, retain these fields and forward-fix.
ALTER TABLE attempt_form_option_order
    ADD COLUMN fingerprint_version INT NULL,
    ADD COLUMN form_fingerprint VARBINARY(32) NULL,
    ADD CONSTRAINT chk_form_fingerprint_pair CHECK (
        (fingerprint_version IS NULL AND form_fingerprint IS NULL)
        OR (fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND form_fingerprint IS NOT NULL AND OCTET_LENGTH(form_fingerprint) = 32)
    );
