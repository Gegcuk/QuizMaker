-- Initialize QuizMaker database
-- This script runs when MySQL container starts for the first time

-- Create database if it doesn't exist (should already be created by MYSQL_DATABASE env var)
CREATE DATABASE IF NOT EXISTS quizmakerdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- User creation and privileges are automatically handled by MySQL entrypoint
-- using MYSQL_USER and MYSQL_PASSWORD environment variables

-- Set timezone
SET GLOBAL time_zone = '+00:00';

-- Basic MySQL optimization (some settings moved to command line)
-- Note: innodb_buffer_pool_size and max_connections are set via command line
