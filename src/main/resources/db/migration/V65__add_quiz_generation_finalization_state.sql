-- A finalization claim is persisted before quiz assembly so an interrupted
-- entitlement decision can be compensated without exposing a false success.
-- Some focused migration tests intentionally baseline only document tables at
-- version 63. Those schemas do not represent a QuizMaker deployment, so leave
-- them untouched rather than making an unrelated document migration fail.
SET @quiz_generation_jobs_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
);

SET @finalization_state_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
      AND column_name = 'finalization_state'
);
SET @finalization_started_at_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
      AND column_name = 'finalization_started_at'
);
SET @finalization_completed_at_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
      AND column_name = 'finalization_completed_at'
);
SET @finalization_error_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
      AND column_name = 'finalization_error'
);
SET @finalization_recovery_index_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
      AND index_name = 'idx_quiz_generation_finalization_recovery'
);

SET @finalization_schema_clauses := CONCAT_WS(', ',
    IF(@finalization_state_missing, 'ADD COLUMN finalization_state VARCHAR(24) NOT NULL DEFAULT ''NOT_STARTED''', NULL),
    IF(@finalization_started_at_missing, 'ADD COLUMN finalization_started_at TIMESTAMP NULL', NULL),
    IF(@finalization_completed_at_missing, 'ADD COLUMN finalization_completed_at TIMESTAMP NULL', NULL),
    IF(@finalization_error_missing, 'ADD COLUMN finalization_error VARCHAR(255) NULL', NULL),
    IF(@finalization_recovery_index_missing, 'ADD INDEX idx_quiz_generation_finalization_recovery (finalization_state, finalization_started_at)', NULL)
);

SET @finalization_schema_sql := IF(
    @quiz_generation_jobs_exists = 1 AND @finalization_schema_clauses <> '',
    CONCAT('ALTER TABLE quiz_generation_jobs ', @finalization_schema_clauses),
    'SELECT 1'
);
PREPARE finalization_schema_statement FROM @finalization_schema_sql;
EXECUTE finalization_schema_statement;
DEALLOCATE PREPARE finalization_schema_statement;

-- Historical rows keep their existing visibility. We do not infer that a
-- pre-migration completed job has a matching ledger entry; paid rows are made
-- reviewable instead of being automatically charged, deleted, or hidden.
SET @finalization_backfill_sql := IF(
    @quiz_generation_jobs_exists = 1,
    'UPDATE quiz_generation_jobs
     SET finalization_state = CASE
         WHEN status = ''CANCELLED'' THEN ''CANCELLED''
         WHEN status = ''FAILED'' THEN ''FAILED''
         WHEN status = ''COMPLETED'' AND billing_reservation_id IS NULL THEN ''LEGACY''
         WHEN status = ''COMPLETED'' THEN ''REVIEW_REQUIRED''
         ELSE ''NOT_STARTED''
     END
     WHERE finalization_state = ''NOT_STARTED''',
    'SELECT 1'
);
PREPARE finalization_backfill_statement FROM @finalization_backfill_sql;
EXECUTE finalization_backfill_statement;
DEALLOCATE PREPARE finalization_backfill_statement;
