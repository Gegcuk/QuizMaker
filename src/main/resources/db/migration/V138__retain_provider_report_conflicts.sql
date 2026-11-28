-- Preserve the precision of new starts and retain the first contradictory report on each attempt.
ALTER TABLE quiz_generation_provider_usage
    MODIFY COLUMN recorded_at TIMESTAMP(6) NOT NULL,
    ADD COLUMN first_conflict_at TIMESTAMP(6) NULL,
    ADD COLUMN conflicting_record_state VARCHAR(16) NULL,
    ADD COLUMN conflicting_provider_llm_tokens BIGINT NULL,
    ADD INDEX idx_qgpu_first_conflict (first_conflict_at, id),
    ADD CONSTRAINT chk_qgpu_terminal_conflict CHECK (
        (first_conflict_at IS NULL AND conflicting_record_state IS NULL AND conflicting_provider_llm_tokens IS NULL)
        OR (first_conflict_at IS NOT NULL AND record_state IN ('REPORTED', 'MISSING', 'FAILED')
            AND conflicting_record_state IS NOT NULL
            AND ((conflicting_record_state = 'REPORTED' AND conflicting_provider_llm_tokens IS NOT NULL
                    AND conflicting_provider_llm_tokens >= 0)
                OR (conflicting_record_state IN ('MISSING', 'FAILED') AND conflicting_provider_llm_tokens IS NULL))
            AND NOT (record_state = conflicting_record_state
                AND (provider_llm_tokens <=> conflicting_provider_llm_tokens)))
    );
