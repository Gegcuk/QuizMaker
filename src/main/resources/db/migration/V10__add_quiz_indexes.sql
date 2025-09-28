-- Add indexes to optimize quiz search queries

-- Note: Adjust table/column names to match schema
-- Assuming tables/columns per JPA mappings:
--   quizzes (quiz_id PK, title, description, difficulty, created_at, is_deleted, category_id, creator_id)
--   quiz_tags (quiz_id, tag_id)
--   tags (tag_id PK, tag_name)
--   categories (category_id PK, category_name)
--   users (user_id PK, username)

-- Basic filters and sorting
-- Guard against duplicate index creation on environments where similar indexes may already exist
SET @table_exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes');
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_created_at');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_created_at ON quizzes(created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_difficulty');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_difficulty ON quizzes(difficulty)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_is_deleted');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_is_deleted ON quizzes(is_deleted)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_category_id');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_category_id ON quizzes(category_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_creator_id');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_creator_id ON quizzes(creator_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Title/description prefix searches benefit from indexes, though LIKE %term% can't fully use them
-- quizzes.title (VARCHAR(100)) - full column index (no prefix)
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_title');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_title ON quizzes(title)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- For large VARCHAR columns in utf8mb4, use prefix length to avoid exceeding key length limits
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quizzes' AND INDEX_NAME = 'idx_quizzes_description_prefix');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quizzes_description_prefix ON quizzes(description(191))', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Tag join support
SET @table_exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_tags');
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_tags' AND INDEX_NAME = 'idx_quiz_tags_quiz_id');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quiz_tags_quiz_id ON quiz_tags(quiz_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quiz_tags' AND INDEX_NAME = 'idx_quiz_tags_tag_id');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_quiz_tags_tag_id ON quiz_tags(tag_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Tag/category lookup by name (matches entity columns `name`)
SET @table_exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tags');
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tags' AND INDEX_NAME = 'idx_tags_name');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_tags_name ON tags(tag_name)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @table_exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'categories');
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'categories' AND INDEX_NAME = 'idx_categories_name');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_categories_name ON categories(category_name)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Author lookup
SET @table_exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users');
SET @exists := (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_username');
SET @sql := IF(@table_exists = 1 AND @exists = 0, 'CREATE INDEX idx_users_username ON users(username)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;