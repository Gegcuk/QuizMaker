-- Dark target authority only; no backfill, inference or FK to legacy attempts.
CREATE TABLE attempt_lifecycle_state (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    lifecycle_version SMALLINT NOT NULL,
    row_version BIGINT NOT NULL,
    execution_state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_collection_state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    submitted_at DATETIME(6) NULL,
    CONSTRAINT chk_attempt_lifecycle_version CHECK (lifecycle_version = 1 AND row_version >= 0),
    CONSTRAINT chk_attempt_lifecycle_tuple CHECK (
        (CAST(execution_state AS BINARY) = CAST('CREATED' AS BINARY)
         AND CAST(answer_collection_state AS BINARY) = CAST('NOT_STARTED' AS BINARY)) OR
        (CAST(execution_state AS BINARY) = CAST('IN_PROGRESS' AS BINARY)
         AND CAST(answer_collection_state AS BINARY) IN (CAST('NOT_STARTED' AS BINARY),CAST('IN_PROGRESS' AS BINARY))) OR
        (CAST(execution_state AS BINARY) = CAST('SUBMITTED' AS BINARY)
         AND CAST(answer_collection_state AS BINARY) = CAST('COMPLETE' AS BINARY))
    ),
    CONSTRAINT chk_attempt_lifecycle_times CHECK (
        updated_at >= created_at AND
        ((execution_state = 'CREATED' AND started_at IS NULL AND submitted_at IS NULL) OR
         (execution_state = 'IN_PROGRESS' AND started_at IS NOT NULL AND submitted_at IS NULL
          AND started_at >= created_at AND started_at <= updated_at) OR
         (execution_state = 'SUBMITTED' AND started_at IS NOT NULL AND submitted_at IS NOT NULL
          AND started_at >= created_at AND submitted_at >= started_at AND submitted_at <= updated_at))
    )
) ENGINE=InnoDB;
