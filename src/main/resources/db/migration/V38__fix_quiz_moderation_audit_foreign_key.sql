-- Fix foreign key constraint in quiz_moderation_audit table
-- The original constraint referenced quizzes(id) but should reference quizzes(quiz_id)

-- Check if the constraint exists and drop it if it does
SET @constraint_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage 
    WHERE table_schema = DATABASE() 
    AND table_name = 'quiz_moderation_audit' 
    AND constraint_name = 'fk_quiz_moderation_audit_quiz'
);

SET @sql = IF(@constraint_exists > 0,
    'ALTER TABLE quiz_moderation_audit DROP FOREIGN KEY fk_quiz_moderation_audit_quiz',
    'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add the correct foreign key constraint
ALTER TABLE quiz_moderation_audit 
ADD CONSTRAINT fk_quiz_moderation_audit_quiz 
FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE;
