-- Additive/dark. Historical rows are not assigned invented timing facts. A verified saved NONE
-- policy remains untimed; a timed policy without its exact checkpoint fails closed.
CREATE TABLE attempt_timing (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    policy_snapshot_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    duration_seconds BIGINT NULL,
    duration_nanos INT NULL,
    overall_deadline DATETIME(6) NULL,
    duration_deadline DATETIME(6) NULL,
    effective_deadline DATETIME(6) NOT NULL,
    grace_seconds INT NOT NULL,
    INDEX ix_attempt_timing_due (effective_deadline, attempt_id),
    CONSTRAINT fk_timing_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT fk_timing_snapshot FOREIGN KEY (policy_snapshot_id) REFERENCES attempt_policy_snapshot(id),
    CONSTRAINT ck_attempt_timing CHECK (schema_version = 1 AND grace_seconds = 0
        AND effective_deadline > started_at
        AND ((duration_seconds IS NULL AND duration_nanos IS NULL AND duration_deadline IS NULL)
          OR (duration_seconds IS NOT NULL AND duration_nanos IS NOT NULL AND duration_deadline IS NOT NULL
            AND duration_seconds >= 0 AND duration_nanos BETWEEN 0 AND 999999999
            AND MOD(duration_nanos,1000) = 0 AND (duration_seconds > 0 OR duration_nanos > 0)
            AND duration_deadline > started_at))
        AND (duration_deadline IS NOT NULL OR overall_deadline IS NOT NULL)
        AND (overall_deadline IS NULL OR overall_deadline > started_at)
        AND effective_deadline = CASE WHEN duration_deadline IS NULL THEN overall_deadline
            WHEN overall_deadline IS NULL THEN duration_deadline ELSE LEAST(duration_deadline,overall_deadline) END)
) ENGINE=InnoDB;
-- After target writes, keep the table and recover with a forward fix; no destructive downgrade.
