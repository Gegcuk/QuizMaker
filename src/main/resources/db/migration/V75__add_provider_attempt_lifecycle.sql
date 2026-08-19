-- Provider usage remains operational telemetry and never changes customer billing.
-- recorded_at is the durable start time for new rows and remains unchanged for legacy rows.
ALTER TABLE quiz_generation_provider_usage
    DROP CHECK chk_qgpu_record,
    ADD CONSTRAINT chk_qgpu_record CHECK (
        (record_state = 'REPORTED' AND provider_llm_tokens IS NOT NULL AND provider_llm_tokens >= 0)
        OR (record_state IN ('STARTED', 'MISSING', 'FAILED') AND provider_llm_tokens IS NULL)
    );
