-- Add missing columns to quiz_generation_jobs table

SET @schema := DATABASE();
SET @tbl := 'quiz_generation_jobs';

-- Add actual_tokens column if it doesn't exist
SET @col := 'actual_tokens';
SET @exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema AND table_name=@tbl AND column_name=@col
);
SET @sql := IF(@exists=0,
    'ALTER TABLE quiz_generation_jobs ADD COLUMN actual_tokens BIGINT NULL',
    'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add was_capped column if it doesn't exist
SET @col := 'was_capped';
SET @exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema AND table_name=@tbl AND column_name=@col
);
SET @sql := IF(@exists=0,
    'ALTER TABLE quiz_generation_jobs ADD COLUMN was_capped BOOLEAN NULL',
    'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
