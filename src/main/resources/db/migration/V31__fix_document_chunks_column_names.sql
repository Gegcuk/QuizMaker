-- Fix column names in document_chunks table to match JPA entity field names

-- Check if table exists and has the wrong column names
SET @table_exists = (
    SELECT COUNT(*) 
    FROM information_schema.tables 
    WHERE table_schema = DATABASE() 
    AND table_name = 'document_chunks'
);

-- Only proceed if table exists
SET @sql = IF(@table_exists > 0,
    'ALTER TABLE document_chunks 
     CHANGE COLUMN chunk_index chunkIndex INT NOT NULL,
     CHANGE COLUMN start_page startPage INT NOT NULL,
     CHANGE COLUMN end_page endPage INT NOT NULL,
     CHANGE COLUMN word_count wordCount INT NOT NULL,
     CHANGE COLUMN character_count characterCount INT NOT NULL,
     CHANGE COLUMN created_at createdAt TIMESTAMP NOT NULL,
     CHANGE COLUMN chapter_title chapterTitle VARCHAR(255) NULL,
     CHANGE COLUMN section_title sectionTitle VARCHAR(255) NULL,
     CHANGE COLUMN chapter_number chapterNumber INT NULL,
     CHANGE COLUMN section_number sectionNumber INT NULL,
     CHANGE COLUMN chunk_type chunkType VARCHAR(20) NULL',
    'SELECT "Document_chunks table does not exist, skipping column rename" as message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
