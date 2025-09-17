-- Seed roles and permissions from canonical policy manifest
-- This migration ensures consistent role/permission setup across environments

-- First, ensure all permissions exist
INSERT IGNORE INTO permissions (permission_name, description, resource, action) VALUES
-- Quiz Permissions
('QUIZ_READ', 'View quizzes', 'quiz', 'read'),
('QUIZ_CREATE', 'Create quizzes', 'quiz', 'create'),
('QUIZ_UPDATE', 'Update own quizzes', 'quiz', 'update'),
('QUIZ_DELETE', 'Delete own quizzes', 'quiz', 'delete'),
('QUIZ_PUBLISH', 'Publish quizzes', 'quiz', 'publish'),
('QUIZ_MODERATE', 'Moderate any quiz', 'quiz', 'moderate'),
('QUIZ_ADMIN', 'Full quiz administration', 'quiz', 'admin'),

-- Question Permissions
('QUESTION_READ', 'View questions', 'question', 'read'),
('QUESTION_CREATE', 'Create questions', 'question', 'create'),
('QUESTION_UPDATE', 'Update own questions', 'question', 'update'),
('QUESTION_DELETE', 'Delete own questions', 'question', 'delete'),
('QUESTION_MODERATE', 'Moderate any question', 'question', 'moderate'),
('QUESTION_ADMIN', 'Full question administration', 'question', 'admin'),

-- Category Permissions
('CATEGORY_READ', 'View categories', 'category', 'read'),
('CATEGORY_CREATE', 'Create categories', 'category', 'create'),
('CATEGORY_UPDATE', 'Update categories', 'category', 'update'),
('CATEGORY_DELETE', 'Delete categories', 'category', 'delete'),
('CATEGORY_ADMIN', 'Full category administration', 'category', 'admin'),

-- Tag Permissions
('TAG_READ', 'View tags', 'tag', 'read'),
('TAG_CREATE', 'Create tags', 'tag', 'create'),
('TAG_UPDATE', 'Update tags', 'tag', 'update'),
('TAG_DELETE', 'Delete tags', 'tag', 'delete'),
('TAG_ADMIN', 'Full tag administration', 'tag', 'admin'),

-- User Permissions
('USER_READ', 'View user profiles', 'user', 'read'),
('USER_UPDATE', 'Update own profile', 'user', 'update'),
('USER_DELETE', 'Delete own account', 'user', 'delete'),
('USER_MANAGE', 'Manage other users', 'user', 'manage'),
('USER_ADMIN', 'Full user administration', 'user', 'admin'),

-- Comment Permissions
('COMMENT_READ', 'View comments', 'comment', 'read'),
('COMMENT_CREATE', 'Create comments', 'comment', 'create'),
('COMMENT_UPDATE', 'Update own comments', 'comment', 'update'),
('COMMENT_DELETE', 'Delete own comments', 'comment', 'delete'),
('COMMENT_MODERATE', 'Moderate any comment', 'comment', 'moderate'),

-- Attempt Permissions
('ATTEMPT_CREATE', 'Take quizzes', 'attempt', 'create'),
('ATTEMPT_READ', 'View own attempts', 'attempt', 'read'),
('ATTEMPT_READ_ALL', 'View all attempts', 'attempt', 'read_all'),
('ATTEMPT_DELETE', 'Delete attempts', 'attempt', 'delete'),

-- Social Permissions
('BOOKMARK_CREATE', 'Create bookmarks', 'bookmark', 'create'),
('BOOKMARK_READ', 'View bookmarks', 'bookmark', 'read'),
('BOOKMARK_DELETE', 'Delete bookmarks', 'bookmark', 'delete'),
('FOLLOW_CREATE', 'Follow users', 'follow', 'create'),
('FOLLOW_DELETE', 'Unfollow users', 'follow', 'delete'),

-- Admin Permissions
('ROLE_READ', 'View roles', 'role', 'read'),
('ROLE_CREATE', 'Create roles', 'role', 'create'),
('ROLE_UPDATE', 'Update roles', 'role', 'update'),
('ROLE_DELETE', 'Delete roles', 'role', 'delete'),
('ROLE_ASSIGN', 'Assign roles to users', 'role', 'assign'),

('PERMISSION_READ', 'View permissions', 'permission', 'read'),
('PERMISSION_CREATE', 'Create permissions', 'permission', 'create'),
('PERMISSION_UPDATE', 'Update permissions', 'permission', 'update'),
('PERMISSION_DELETE', 'Delete permissions', 'permission', 'delete'),

-- Billing Permissions
('BILLING_READ', 'View billing information and balance', 'billing', 'read'),
('BILLING_WRITE', 'Manage billing operations and purchases', 'billing', 'write'),

-- System Permissions
('AUDIT_READ', 'View audit logs', 'audit', 'read'),
('SYSTEM_ADMIN', 'Full system administration', 'system', 'admin'),

-- Notification Permissions
('NOTIFICATION_READ', 'View notifications', 'notification', 'read'),
('NOTIFICATION_CREATE', 'Create notifications', 'notification', 'create'),
('NOTIFICATION_ADMIN', 'Manage notifications', 'notification', 'admin');

-- Create roles if they don't exist
INSERT IGNORE INTO roles (role_name, description, is_default) VALUES
('ROLE_USER', 'Basic user role', TRUE),
('ROLE_QUIZ_CREATOR', 'Quiz creator role', FALSE),
('ROLE_MODERATOR', 'Moderator role', FALSE),
('ROLE_ADMIN', 'Administrator role', FALSE),
('ROLE_SUPER_ADMIN', 'Super administrator role', FALSE);

-- Create role_permissions junction table if it doesn't exist
CREATE TABLE IF NOT EXISTS role_permissions (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES permissions(permission_id) ON DELETE CASCADE
);

-- Clear existing role-permission mappings to ensure clean state
DELETE FROM role_permissions;

-- Assign permissions to ROLE_USER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.role_name = 'ROLE_USER'
AND p.permission_name IN (
    'QUIZ_READ', 'QUESTION_READ', 'CATEGORY_READ', 'TAG_READ',
    'USER_READ', 'USER_UPDATE', 'USER_DELETE',
    'COMMENT_READ', 'COMMENT_CREATE', 'COMMENT_UPDATE', 'COMMENT_DELETE',
    'ATTEMPT_CREATE', 'ATTEMPT_READ',
    'BOOKMARK_CREATE', 'BOOKMARK_READ', 'BOOKMARK_DELETE',
    'FOLLOW_CREATE', 'FOLLOW_DELETE',
    'NOTIFICATION_READ', 'BILLING_READ', 'BILLING_WRITE'
);

-- Assign permissions to ROLE_QUIZ_CREATOR (includes all USER permissions plus creator permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.role_name = 'ROLE_QUIZ_CREATOR'
AND p.permission_name IN (
    'QUIZ_READ', 'QUESTION_READ', 'CATEGORY_READ', 'TAG_READ',
    'USER_READ', 'USER_UPDATE', 'USER_DELETE',
    'COMMENT_READ', 'COMMENT_CREATE', 'COMMENT_UPDATE', 'COMMENT_DELETE',
    'ATTEMPT_CREATE', 'ATTEMPT_READ',
    'BOOKMARK_CREATE', 'BOOKMARK_READ', 'BOOKMARK_DELETE',
    'FOLLOW_CREATE', 'FOLLOW_DELETE',
    'NOTIFICATION_READ', 'BILLING_READ', 'BILLING_WRITE',
    'QUIZ_CREATE', 'QUIZ_UPDATE', 'QUIZ_DELETE',
    'QUESTION_CREATE', 'QUESTION_UPDATE', 'QUESTION_DELETE',
    'CATEGORY_CREATE', 'TAG_CREATE'
);

-- Assign permissions to ROLE_MODERATOR (includes all QUIZ_CREATOR permissions plus moderation permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.role_name = 'ROLE_MODERATOR'
AND p.permission_name IN (
    'QUIZ_READ', 'QUESTION_READ', 'CATEGORY_READ', 'TAG_READ',
    'USER_READ', 'USER_UPDATE', 'USER_DELETE',
    'COMMENT_READ', 'COMMENT_CREATE', 'COMMENT_UPDATE', 'COMMENT_DELETE',
    'ATTEMPT_CREATE', 'ATTEMPT_READ',
    'BOOKMARK_CREATE', 'BOOKMARK_READ', 'BOOKMARK_DELETE',
    'FOLLOW_CREATE', 'FOLLOW_DELETE',
    'NOTIFICATION_READ', 'BILLING_READ', 'BILLING_WRITE',
    'QUIZ_CREATE', 'QUIZ_UPDATE', 'QUIZ_DELETE',
    'QUESTION_CREATE', 'QUESTION_UPDATE', 'QUESTION_DELETE',
    'CATEGORY_CREATE', 'TAG_CREATE',
    'QUIZ_MODERATE', 'QUESTION_MODERATE', 'COMMENT_MODERATE',
    'CATEGORY_UPDATE', 'TAG_UPDATE', 'ATTEMPT_READ_ALL', 'USER_MANAGE'
);

-- Assign permissions to ROLE_ADMIN (includes all MODERATOR permissions plus admin permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.role_name = 'ROLE_ADMIN'
AND p.permission_name IN (
    'QUIZ_READ', 'QUESTION_READ', 'CATEGORY_READ', 'TAG_READ',
    'USER_READ', 'USER_UPDATE', 'USER_DELETE',
    'COMMENT_READ', 'COMMENT_CREATE', 'COMMENT_UPDATE', 'COMMENT_DELETE',
    'ATTEMPT_CREATE', 'ATTEMPT_READ',
    'BOOKMARK_CREATE', 'BOOKMARK_READ', 'BOOKMARK_DELETE',
    'FOLLOW_CREATE', 'FOLLOW_DELETE',
    'NOTIFICATION_READ', 'BILLING_READ', 'BILLING_WRITE',
    'QUIZ_CREATE', 'QUIZ_UPDATE', 'QUIZ_DELETE',
    'QUESTION_CREATE', 'QUESTION_UPDATE', 'QUESTION_DELETE',
    'CATEGORY_CREATE', 'TAG_CREATE',
    'QUIZ_MODERATE', 'QUESTION_MODERATE', 'COMMENT_MODERATE',
    'CATEGORY_UPDATE', 'TAG_UPDATE', 'ATTEMPT_READ_ALL', 'USER_MANAGE',
    'QUIZ_ADMIN', 'QUESTION_ADMIN', 'CATEGORY_ADMIN', 'TAG_ADMIN', 'USER_ADMIN',
    'ROLE_READ', 'ROLE_ASSIGN', 'PERMISSION_READ', 'AUDIT_READ',
    'NOTIFICATION_CREATE', 'NOTIFICATION_ADMIN', 'ATTEMPT_DELETE'
);

-- Assign permissions to ROLE_SUPER_ADMIN (includes all ADMIN permissions plus system permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.role_name = 'ROLE_SUPER_ADMIN'
AND p.permission_name IN (
    'QUIZ_READ', 'QUESTION_READ', 'CATEGORY_READ', 'TAG_READ',
    'USER_READ', 'USER_UPDATE', 'USER_DELETE',
    'COMMENT_READ', 'COMMENT_CREATE', 'COMMENT_UPDATE', 'COMMENT_DELETE',
    'ATTEMPT_CREATE', 'ATTEMPT_READ',
    'BOOKMARK_CREATE', 'BOOKMARK_READ', 'BOOKMARK_DELETE',
    'FOLLOW_CREATE', 'FOLLOW_DELETE',
    'NOTIFICATION_READ', 'BILLING_READ', 'BILLING_WRITE',
    'QUIZ_CREATE', 'QUIZ_UPDATE', 'QUIZ_DELETE',
    'QUESTION_CREATE', 'QUESTION_UPDATE', 'QUESTION_DELETE',
    'CATEGORY_CREATE', 'TAG_CREATE',
    'QUIZ_MODERATE', 'QUESTION_MODERATE', 'COMMENT_MODERATE',
    'CATEGORY_UPDATE', 'TAG_UPDATE', 'ATTEMPT_READ_ALL', 'USER_MANAGE',
    'QUIZ_ADMIN', 'QUESTION_ADMIN', 'CATEGORY_ADMIN', 'TAG_ADMIN', 'USER_ADMIN',
    'ROLE_READ', 'ROLE_ASSIGN', 'PERMISSION_READ', 'AUDIT_READ',
    'NOTIFICATION_CREATE', 'NOTIFICATION_ADMIN', 'ATTEMPT_DELETE',
    'SYSTEM_ADMIN', 'ROLE_CREATE', 'ROLE_UPDATE', 'ROLE_DELETE',
    'PERMISSION_CREATE', 'PERMISSION_UPDATE', 'PERMISSION_DELETE'
);
