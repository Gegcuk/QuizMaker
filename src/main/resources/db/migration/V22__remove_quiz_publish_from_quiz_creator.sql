-- Remove QUIZ_PUBLISH permission from ROLE_QUIZ_CREATOR
-- This enforces that quiz creators must go through moderation to publish quizzes

DELETE rp FROM role_permissions rp
INNER JOIN roles r ON rp.role_id = r.role_id
INNER JOIN permissions p ON rp.permission_id = p.permission_id
WHERE r.role_name = 'ROLE_QUIZ_CREATOR'
  AND p.permission_name = 'QUIZ_PUBLISH';
