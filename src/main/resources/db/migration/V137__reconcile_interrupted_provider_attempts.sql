-- Preserve the record-state vocabulary for older writers. STARTED with unknown_at
-- is an unresolved outcome. A late terminal report retains the marker for audit.
ALTER TABLE quiz_generation_provider_usage
    ADD COLUMN execution_fence BIGINT NULL,
    ADD COLUMN unknown_at TIMESTAMP(6) NULL,
    ADD COLUMN terminal_at TIMESTAMP(6) NULL,
    ADD INDEX idx_qgpu_state_unknown_id (record_state, unknown_at, id),
    ADD CONSTRAINT chk_qgpu_execution_fence CHECK (execution_fence IS NULL OR execution_fence > 0),
    ADD CONSTRAINT chk_qgpu_terminal_time CHECK (record_state <> 'STARTED' OR terminal_at IS NULL);
