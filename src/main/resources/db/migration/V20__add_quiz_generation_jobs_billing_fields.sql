--
-- Idempotent migration: create table if it does not exist,
-- then add billing columns and indexes only if missing.
-- Works both for fresh DBs and for DBs where the table already existed (e.g., from dev hibernate auto DDL).
--

-- 1) Create base table if missing (includes billing columns so ALTERs below are no-ops)
CREATE TABLE IF NOT EXISTS quiz_generation_jobs (
  id BINARY(16) NOT NULL,
  document_id BINARY(16) NOT NULL,
  username VARCHAR(50) NOT NULL,
  status ENUM('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  total_chunks INT NULL,
  processed_chunks INT NOT NULL DEFAULT 0,
  error_message TEXT NULL,
  generated_quiz_id BINARY(16) NULL,
  request_data JSON NULL,
  estimated_completion TIMESTAMP NULL,
  progress_percentage DOUBLE NULL,
  current_chunk VARCHAR(255) NULL,
  total_questions_generated INT NOT NULL DEFAULT 0,
  generation_time_seconds BIGINT NULL,
  -- Billing fields (as per this migration)
  billing_reservation_id BINARY(16) NULL,
  reservation_expires_at TIMESTAMP NULL,
  billing_estimated_tokens BIGINT NOT NULL DEFAULT 0,
  billing_committed_tokens BIGINT NOT NULL DEFAULT 0,
  billing_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
  billing_idempotency_keys JSON NULL,
  last_billing_error JSON NULL,
  input_prompt_tokens BIGINT NULL,
  estimation_version VARCHAR(32) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_qgj_user_by_username FOREIGN KEY (username) REFERENCES users(username),
  CONSTRAINT chk_billing_state CHECK (billing_state IN ('NONE','RESERVED','COMMITTED','RELEASED'))
) ENGINE=InnoDB;

-- 2) Add columns (no-op if table creation above already added them)
ALTER TABLE quiz_generation_jobs 
  ADD COLUMN IF NOT EXISTS billing_reservation_id BINARY(16) NULL,
  ADD COLUMN IF NOT EXISTS reservation_expires_at TIMESTAMP NULL,
  ADD COLUMN IF NOT EXISTS billing_estimated_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS billing_committed_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS billing_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
  ADD COLUMN IF NOT EXISTS billing_idempotency_keys JSON NULL,
  ADD COLUMN IF NOT EXISTS last_billing_error JSON NULL,
  ADD COLUMN IF NOT EXISTS input_prompt_tokens BIGINT NULL,
  ADD COLUMN IF NOT EXISTS estimation_version VARCHAR(32) NULL;

-- 3) Add CHECK constraint for billing_state if missing (MySQL lacks IF NOT EXISTS for constraints)
SET @schema := DATABASE();
SET @tbl := 'quiz_generation_jobs';
SET @chk := 'chk_billing_state';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.table_constraints
  WHERE table_schema=@schema AND table_name=@tbl AND constraint_type='CHECK' AND constraint_name=@chk
);
SET @sql := IF(@exists=0,
  'ALTER TABLE quiz_generation_jobs ADD CONSTRAINT chk_billing_state CHECK (billing_state IN (''NONE'',''RESERVED'',''COMMITTED'',''RELEASED''))',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) Indexes (idempotent)
-- Unique index on billing_reservation_id
SET @idx_name := 'idx_quiz_generation_jobs_billing_reservation_id';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE UNIQUE INDEX idx_quiz_generation_jobs_billing_reservation_id ON quiz_generation_jobs (billing_reservation_id)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Index for billing state queries
SET @idx_name := 'idx_quiz_generation_jobs_billing_state';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_quiz_generation_jobs_billing_state ON quiz_generation_jobs (billing_state)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Index for reservation expiry sweeper
SET @idx_name := 'idx_quiz_generation_jobs_reservation_expires_at';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_quiz_generation_jobs_reservation_expires_at ON quiz_generation_jobs (reservation_expires_at)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Helpful general indexes for typical queries (no-op if they exist)
SET @idx_name := 'idx_qgj_username_started_at';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_qgj_username_started_at ON quiz_generation_jobs (username, started_at)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_name := 'idx_qgj_status_started_at';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_qgj_status_started_at ON quiz_generation_jobs (status, started_at)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
