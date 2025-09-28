-- Initialize QuizMaker database
-- This script runs when MySQL container starts for the first time

-- Create database if it doesn't exist (should already be created by MYSQL_DATABASE env var)
CREATE DATABASE IF NOT EXISTS quizmakerdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- User creation and privileges are automatically handled by MySQL entrypoint
-- using MYSQL_USER and MYSQL_PASSWORD environment variables
-- But we need to ensure the user can connect from any host in Docker network
-- The user is created by MySQL entrypoint, we just need to grant privileges from any host
-- We'll use a dynamic approach to get the actual username from environment
SET @user_name = (SELECT SUBSTRING_INDEX(USER(), '@', 1));
SET @sql = CONCAT('GRANT ALL PRIVILEGES ON quizmakerdb.* TO ''', @user_name, '''@''%''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
FLUSH PRIVILEGES;

-- Set timezone
SET GLOBAL time_zone = '+00:00';

-- Basic MySQL optimization (some settings moved to command line)
-- Note: innodb_buffer_pool_size and max_connections are set via command line
