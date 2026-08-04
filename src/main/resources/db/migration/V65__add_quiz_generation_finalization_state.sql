-- A finalization claim is persisted before quiz assembly so an interrupted
-- entitlement decision can be compensated without exposing a false success.
ALTER TABLE quiz_generation_jobs
    ADD COLUMN finalization_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN finalization_started_at TIMESTAMP NULL,
    ADD COLUMN finalization_completed_at TIMESTAMP NULL,
    ADD COLUMN finalization_error VARCHAR(255) NULL,
    ADD INDEX idx_quiz_generation_finalization_recovery (finalization_state, finalization_started_at);

-- Historical rows keep their existing visibility. We do not infer that a
-- pre-migration completed job has a matching ledger entry; paid rows are made
-- reviewable instead of being automatically charged, deleted, or hidden.
UPDATE quiz_generation_jobs
SET finalization_state = CASE
    WHEN status = 'CANCELLED' THEN 'CANCELLED'
    WHEN status = 'FAILED' THEN 'FAILED'
    WHEN status = 'COMPLETED' AND billing_reservation_id IS NULL THEN 'LEGACY'
    WHEN status = 'COMPLETED' THEN 'REVIEW_REQUIRED'
    ELSE 'NOT_STARTED'
END
WHERE finalization_state = 'NOT_STARTED';
