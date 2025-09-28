-- Initialize QuizMaker database
-- This script runs when MySQL container starts for the first time

-- Debug: Show environment variables
SELECT 'DEBUG: MySQL init script started' as debug_message;
SELECT CONCAT('DEBUG: MYSQL_USER = ', IFNULL(@MYSQL_USER, 'NULL')) as debug_message;
SELECT CONCAT('DEBUG: MYSQL_PASSWORD = ', IFNULL(@MYSQL_PASSWORD, 'NULL')) as debug_message;
SELECT CONCAT('DEBUG: MYSQL_DATABASE = ', IFNULL(@MYSQL_DATABASE, 'NULL')) as debug_message;

-- Create database if it doesn't exist (should already be created by MYSQL_DATABASE env var)
CREATE DATABASE IF NOT EXISTS quizmakerdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- User creation and privileges are automatically handled by MySQL entrypoint
-- using MYSQL_USER and MYSQL_PASSWORD environment variables
-- But we need to ensure the user can connect from any host in Docker network
-- The user is created by MySQL entrypoint, we just need to grant privileges from any host
-- We'll use the actual username from the environment variable

-- Debug: Show existing users before granting privileges
SELECT 'DEBUG: Existing users before grant:' as debug_message;
SELECT User, Host FROM mysql.user WHERE User = '${MYSQL_USER}';

-- Grant privileges from any host
SELECT CONCAT('DEBUG: Granting privileges to user: ', '${MYSQL_USER}') as debug_message;
GRANT ALL PRIVILEGES ON quizmakerdb.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;

-- Debug: Show users after granting privileges
SELECT 'DEBUG: Users after grant:' as debug_message;
SELECT User, Host FROM mysql.user WHERE User = '${MYSQL_USER}';

-- Debug: Show privileges for the user
SELECT 'DEBUG: Privileges for user:' as debug_message;
SHOW GRANTS FOR '${MYSQL_USER}'@'%';

-- Set timezone
SET GLOBAL time_zone = '+00:00';

-- Basic MySQL optimization (some settings moved to command line)
-- Note: innodb_buffer_pool_size and max_connections are set via command line
