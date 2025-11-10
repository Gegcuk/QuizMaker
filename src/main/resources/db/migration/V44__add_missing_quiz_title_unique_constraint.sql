-- Add missing unique constraint on quiz title per creator
-- Ensures same user cannot create multiple quizzes with the same title

-- Simple approach: Try to add constraint, ignore if it already exists
-- MySQL will error with code 1061 if constraint exists, which we handle in the application

SET @constraint_exists = (
    SELECT COUNT(*) 
    FROM information_schema.TABLE_CONSTRAINTS 
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'quizzes'
      AND CONSTRAINT_NAME = 'uq_quiz_creator_title'
);

SET @sql = IF(@constraint_exists = 0,
    'ALTER TABLE quizzes ADD CONSTRAINT uq_quiz_creator_title UNIQUE (creator_id, title)',
    'SELECT "Constraint already exists, skipping" as message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

