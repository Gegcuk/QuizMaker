-- Initialize QuizMaker database
-- This script runs when MySQL container starts for the first time

-- Set timezone first
SET GLOBAL time_zone = '+00:00';

-- Create database with proper charset (should already exist from MYSQL_DATABASE)
CREATE DATABASE IF NOT EXISTS quizmakerdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Debug markers to make troubleshooting easier
SELECT 'DEBUG: MySQL initialization started' as message;
SELECT 'DEBUG: Database created/verified: quizmakerdb' as message;

-- Show non-system users so we know what the entrypoint created for us
SELECT 'DEBUG: Existing users after entrypoint setup:' as message;
SELECT User, Host FROM mysql.user WHERE User NOT IN ('root', 'mysql.session', 'mysql.sys', 'mysql.infoschema', '');

SELECT 'DEBUG: MySQL initialization completed successfully' as message;
