-- Initialize QuizMaker database
-- This script runs when MySQL container starts for the first time

-- Set timezone first
SET GLOBAL time_zone = '+00:00';

-- Create database with proper charset (should already exist from MYSQL_DATABASE)
CREATE DATABASE IF NOT EXISTS quizmakerdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Debug: Show environment info
SELECT 'DEBUG: MySQL initialization started' as message;
SELECT 'DEBUG: Database created/verified: quizmakerdb' as message;

-- The MySQL Docker entrypoint automatically creates the user specified by MYSQL_USER
-- with the password from MYSQL_PASSWORD, but only grants access from localhost.
-- We need to grant access from any host (%) for Docker networking to work.

-- Debug: Show what users exist before our modifications
SELECT 'DEBUG: Users before network access grants:' as message;
SELECT User, Host FROM mysql.user WHERE User NOT IN ('root', 'mysql.session', 'mysql.sys', 'mysql.infoschema', '');

-- Grant network access to any non-system users that were created by the entrypoint
-- This is more secure than creating hardcoded users
DELIMITER $$
CREATE PROCEDURE GrantNetworkAccessToExistingUsers()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE username VARCHAR(255);
    DECLARE cur CURSOR FOR 
        SELECT DISTINCT User FROM mysql.user 
        WHERE User NOT IN ('root', 'mysql.session', 'mysql.sys', 'mysql.infoschema', '')
        AND Host = 'localhost';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    SELECT 'DEBUG: Starting network access grants...' as message;
    
    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO username;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        -- Create user for network access if it doesn't exist
        SET @sql = CONCAT('CREATE USER IF NOT EXISTS ''', username, '''@''%'' IDENTIFIED BY (SELECT authentication_string FROM mysql.user WHERE User = ''', username, ''' AND Host = ''localhost'' LIMIT 1)');
        -- Note: The above won't work due to MySQL limitations, so we'll use a different approach
        
        -- Grant privileges from any host for this user (assumes password is same as localhost user)
        SET @sql = CONCAT('GRANT ALL PRIVILEGES ON quizmakerdb.* TO ''', username, '''@''%''');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        
        SELECT CONCAT('DEBUG: Granted network access to user: ', username) as message;
    END LOOP;
    CLOSE cur;
    
    FLUSH PRIVILEGES;
    SELECT 'DEBUG: Network access grants completed' as message;
END$$
DELIMITER ;

-- Execute the procedure
CALL GrantNetworkAccessToExistingUsers();

-- Clean up
DROP PROCEDURE GrantNetworkAccessToExistingUsers;

-- Final debug: Show all users and their hosts
SELECT 'DEBUG: Final user configuration:' as message;
SELECT User, Host, 
       CASE WHEN Host = '%' THEN 'Network Access' ELSE 'Local Only' END as Access_Type
FROM mysql.user 
WHERE User NOT IN ('root', 'mysql.session', 'mysql.sys', 'mysql.infoschema', '')
ORDER BY User, Host;

-- Show database privileges
SELECT 'DEBUG: Database privileges:' as message;
SELECT User, Host, Db, 
       CONCAT(Select_priv, Insert_priv, Update_priv, Delete_priv) as CRUD_Privs
FROM mysql.db 
WHERE Db = 'quizmakerdb'
ORDER BY User, Host;

SELECT 'DEBUG: MySQL initialization completed successfully' as message;
