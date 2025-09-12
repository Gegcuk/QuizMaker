-- Add billing fields to quiz_generation_jobs table for token consumption tracking
-- This migration adds the lean V1 billing columns as specified in the token consumption plan

-- Add billing columns to quiz_generation_jobs table
ALTER TABLE quiz_generation_jobs 
ADD COLUMN billing_reservation_id BINARY(16) NULL,
ADD COLUMN reservation_expires_at TIMESTAMP NULL,
ADD COLUMN billing_estimated_tokens BIGINT NOT NULL DEFAULT 0,
ADD COLUMN billing_committed_tokens BIGINT NOT NULL DEFAULT 0,
ADD COLUMN billing_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
ADD COLUMN billing_idempotency_keys JSON NULL,
ADD COLUMN last_billing_error JSON NULL,
ADD COLUMN input_prompt_tokens BIGINT NULL,
ADD COLUMN estimation_version VARCHAR(32) NULL;

-- Add CHECK constraint for billing_state
ALTER TABLE quiz_generation_jobs 
ADD CONSTRAINT chk_billing_state 
CHECK (billing_state IN ('NONE', 'RESERVED', 'COMMITTED', 'RELEASED'));

-- Add unique index on billing_reservation_id (where not null) for safety
CREATE UNIQUE INDEX idx_quiz_generation_jobs_billing_reservation_id 
ON quiz_generation_jobs (billing_reservation_id) 
WHERE billing_reservation_id IS NOT NULL;

-- Add index for billing state queries
CREATE INDEX idx_quiz_generation_jobs_billing_state 
ON quiz_generation_jobs (billing_state);

-- Add index for reservation expiry queries (for sweeper)
CREATE INDEX idx_quiz_generation_jobs_reservation_expires_at 
ON quiz_generation_jobs (reservation_expires_at) 
WHERE reservation_expires_at IS NOT NULL;
