-- Preserve a customer-facing generation quote separately from provider LLM telemetry.
-- Nullable tariff columns keep jobs created before this migration on their legacy settlement path.
ALTER TABLE quiz_generation_jobs
    ADD COLUMN provider_llm_tokens BIGINT NULL,
    ADD COLUMN billing_tariff_version VARCHAR(100) NULL,
    ADD COLUMN billing_tokens_per_valid_question BIGINT NULL,
    ADD COLUMN billing_quoted_question_count INT NULL,
    ADD COLUMN billing_valid_question_count INT NULL;
