-- Provider usage is operational telemetry, not a customer billing input.
-- Each actual provider response is identified once per job so concurrent work
-- cannot lose increments and duplicate delivery cannot double-count usage.
ALTER TABLE quiz_generation_jobs
    ADD COLUMN provider_usage_state VARCHAR(24) NOT NULL DEFAULT 'NOT_RECORDED' AFTER provider_llm_tokens,
    ADD CONSTRAINT chk_qgj_provider_usage_state CHECK (
        provider_usage_state IN ('NOT_RECORDED', 'COMPLETE', 'INCOMPLETE', 'LEGACY_REVIEW')
    );

-- Jobs already active during rollout may contain unidentifiable prior attempts.
-- Never infer completeness or customer pricing for those executions.
UPDATE quiz_generation_jobs
SET provider_usage_state = CASE
        WHEN billing_tariff_version IS NULL
            OR billing_base_tokens IS NULL
            OR billing_tokens_per_thousand_characters IS NULL
            OR billing_quoted_content_characters IS NULL
            OR billing_quoted_question_type_count IS NULL
            THEN 'LEGACY_REVIEW'
        ELSE 'INCOMPLETE'
    END
WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE quiz_generation_provider_usage (
    id BINARY(16) NOT NULL,
    job_id BINARY(16) NOT NULL,
    provider_attempt_id BINARY(16) NOT NULL,
    record_state VARCHAR(16) NOT NULL,
    provider_llm_tokens BIGINT NULL,
    recorded_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_qgpu_job_attempt UNIQUE (job_id, provider_attempt_id),
    CONSTRAINT fk_qgpu_job FOREIGN KEY (job_id)
        REFERENCES quiz_generation_jobs(id) ON DELETE CASCADE,
    CONSTRAINT chk_qgpu_record CHECK (
        (record_state = 'REPORTED' AND provider_llm_tokens IS NOT NULL AND provider_llm_tokens >= 0)
        OR (record_state = 'MISSING' AND provider_llm_tokens IS NULL)
    ),
    INDEX idx_qgpu_job_recorded (job_id, recorded_at),
    INDEX idx_qgpu_state_recorded (record_state, recorded_at)
) ENGINE=InnoDB;
