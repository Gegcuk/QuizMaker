-- Preserve a customer-facing generation quote separately from provider LLM telemetry.
-- Nullable tariff columns keep jobs created before this migration on their legacy settlement path.
ALTER TABLE quiz_generation_jobs
    ADD COLUMN provider_llm_tokens BIGINT NULL,
    ADD COLUMN billing_tariff_version VARCHAR(100) NULL,
    ADD COLUMN billing_base_tokens BIGINT NULL,
    ADD COLUMN billing_tokens_per_thousand_characters DECIMAL(10,4) NULL,
    ADD COLUMN billing_quoted_content_characters BIGINT NULL,
    ADD COLUMN billing_quoted_question_type_count INT NULL,
    ADD COLUMN billing_accepted_question_type_count INT NULL;
