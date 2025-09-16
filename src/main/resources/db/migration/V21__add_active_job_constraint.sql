-- Add constraint to prevent multiple active jobs per user
-- This ensures only one active job per user at the database level

-- Add a computed column that is username when status is active, NULL otherwise
-- This allows us to create a unique constraint that only applies to active jobs
ALTER TABLE quiz_generation_jobs 
ADD COLUMN active_username VARCHAR(50) GENERATED ALWAYS AS (
    CASE WHEN status IN ('PENDING', 'PROCESSING') THEN username ELSE NULL END
) STORED;

-- Create unique index on the computed column
-- MySQL allows multiple NULLs, so this effectively creates a unique constraint
-- only for active jobs (where active_username is not NULL)
CREATE UNIQUE INDEX idx_quiz_generation_jobs_one_active_per_user 
ON quiz_generation_jobs (active_username);
