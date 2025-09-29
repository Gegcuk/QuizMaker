-- Remove QUIZ_PUBLISH permission from ROLE_QUIZ_CREATOR
-- This enforces that quiz creators must go through moderation to publish quizzes

-- Make this migration safe if role_permissions doesn't exist yet in this environment
SET @schema := DATABASE();
SET @exists := (
  SELECT COUNT(1)
  FROM information_schema.tables
  WHERE table_schema = @schema
    AND table_name = 'role_permissions'
);
SET @sql := IF(@exists > 0,
  'DELETE rp FROM role_permissions rp 
    INNER JOIN roles r ON rp.role_id = r.role_id 
    INNER JOIN permissions p ON rp.permission_id = p.permission_id 
    WHERE r.role_name = ''ROLE_QUIZ_CREATOR'' 
      AND p.permission_name = ''QUIZ_PUBLISH''',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
