-- Add task-level progress tracking columns to quiz_generation_jobs
-- This enables fine-grained progress updates per question type batch instead of just per chunk

ALTER TABLE quiz_generation_jobs
ADD COLUMN total_tasks INT NULL COMMENT 'Total number of question type batches (tasks) to process across all chunks',
ADD COLUMN completed_tasks INT DEFAULT 0 COMMENT 'Number of completed question type batches (tasks)',
ADD COLUMN version BIGINT DEFAULT 0 COMMENT 'Optimistic locking version for concurrent updates';

-- Backfill existing rows: for backward compatibility, set completed_tasks = processed_chunks and total_tasks based on total_chunks
-- This ensures existing jobs maintain their progress percentage calculations
UPDATE quiz_generation_jobs
SET completed_tasks = COALESCE(processed_chunks, 0),
    total_tasks = CASE
        WHEN total_chunks IS NOT NULL AND total_chunks > 0 THEN total_chunks
        ELSE 1
    END
WHERE completed_tasks IS NULL OR total_tasks IS NULL;

-- Add index on task progress for efficient queries
CREATE INDEX idx_quiz_generation_jobs_task_progress ON quiz_generation_jobs(total_tasks, completed_tasks);

