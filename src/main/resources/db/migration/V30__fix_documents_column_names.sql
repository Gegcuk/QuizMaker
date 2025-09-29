-- Fix column names in documents table to match JPA entity field names

-- Check if table exists and has the wrong column names
SET @table_exists = (
    SELECT COUNT(*) 
    FROM information_schema.tables 
    WHERE table_schema = DATABASE() 
    AND table_name = 'documents'
);

-- Only proceed if table exists
SET @sql = IF(@table_exists > 0,
    'ALTER TABLE documents 
     CHANGE COLUMN original_filename originalFilename VARCHAR(255) NOT NULL,
     CHANGE COLUMN content_type contentType VARCHAR(255) NOT NULL,
     CHANGE COLUMN file_size fileSize BIGINT NOT NULL,
     CHANGE COLUMN file_path filePath VARCHAR(500) NOT NULL,
     CHANGE COLUMN uploaded_at uploadedAt TIMESTAMP NOT NULL,
     CHANGE COLUMN processed_at processedAt TIMESTAMP NOT NULL,
     CHANGE COLUMN total_pages totalPages INT NULL,
     CHANGE COLUMN total_chunks totalChunks INT NULL,
     CHANGE COLUMN processing_error processingError TEXT NULL',
    'SELECT "Documents table does not exist, skipping column rename" as message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
