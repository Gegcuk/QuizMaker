-- Add constraint to prevent multiple active jobs per user
-- This ensures only one active job per user at the database level

-- Add a computed column that is username when status is active, NULL otherwise
-- This allows us to create a unique constraint that only applies to active jobs
ALTER TABLE quiz_generation_jobs 
  ADD COLUMN IF NOT EXISTS active_username VARCHAR(50) GENERATED ALWAYS AS (
    CASE WHEN status IN ('PENDING', 'PROCESSING') THEN username ELSE NULL END
  ) STORED;

-- Create unique index on the computed column (idempotent)
SET @schema := DATABASE();
SET @tbl := 'quiz_generation_jobs';
SET @idx_name := 'idx_quiz_generation_jobs_one_active_per_user';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE UNIQUE INDEX idx_quiz_generation_jobs_one_active_per_user ON quiz_generation_jobs (active_username)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
