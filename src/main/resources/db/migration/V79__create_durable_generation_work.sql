-- Bounded metadata identifies source content; no document bytes are rehashed.
ALTER TABLE documents ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 0;

-- Intentionally no backfill of active jobs: only newly accepted work is managed.
CREATE TABLE quiz_generation_work (
    job_id BINARY(16) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    source_revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    executions INT NOT NULL DEFAULT 0,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (job_id),
    INDEX ix_generation_work_due (state, lease_until, created_at),
    CONSTRAINT fk_generation_work_job FOREIGN KEY (job_id)
        REFERENCES quiz_generation_jobs(id) ON DELETE CASCADE,
    CONSTRAINT chk_generation_work_state CHECK (state IN ('READY', 'RUNNING', 'DONE')),
    CONSTRAINT chk_generation_work_executions CHECK (executions BETWEEN 0 AND 2)
) ENGINE=InnoDB;
