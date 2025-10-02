-- Add fields for quiz generation job resilience and cooperative cancellation
ALTER TABLE quiz_generation_jobs
ADD COLUMN has_started_ai_calls BOOLEAN DEFAULT FALSE,
ADD COLUMN first_ai_call_at TIMESTAMP NULL;

-- Add comment for clarity
ALTER TABLE quiz_generation_jobs MODIFY COLUMN has_started_ai_calls BOOLEAN DEFAULT FALSE COMMENT 'Tracks whether any AI/LLM calls have been made for this job';
ALTER TABLE quiz_generation_jobs MODIFY COLUMN first_ai_call_at TIMESTAMP NULL COMMENT 'Timestamp of the first AI/LLM call for token commitment on cancel';

