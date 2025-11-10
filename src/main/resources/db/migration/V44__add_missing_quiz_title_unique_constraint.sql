-- Add missing unique constraint on quiz title per creator
-- This ensures the same user cannot create multiple quizzes with the same title
-- Fixes production issue where constraint wasn't created due to CREATE TABLE IF NOT EXISTS

-- First, check if constraint already exists (MySQL compatible)
-- The constraint might be missing if the quizzes table was created before V26 migration

-- Add the unique constraint if it doesn't exist
-- Note: MySQL doesn't have CREATE UNIQUE INDEX IF NOT EXISTS before 8.0.29
-- So we use a stored procedure to check and add conditionally

DELIMITER //

CREATE PROCEDURE AddQuizTitleConstraint()
BEGIN
    DECLARE constraint_exists INT DEFAULT 0;
    
    -- Check if the constraint already exists
    SELECT COUNT(*) INTO constraint_exists
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'quizzes'
      AND CONSTRAINT_NAME = 'uq_quiz_creator_title'
      AND CONSTRAINT_TYPE = 'UNIQUE';
    
    -- Add constraint only if it doesn't exist
    IF constraint_exists = 0 THEN
        ALTER TABLE quizzes
        ADD CONSTRAINT uq_quiz_creator_title UNIQUE (creator_id, title);
    END IF;
END//

DELIMITER ;

-- Execute the procedure
CALL AddQuizTitleConstraint();

-- Clean up
DROP PROCEDURE AddQuizTitleConstraint;

