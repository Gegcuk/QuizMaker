-- Some deployed databases ran the now-removed V30/V31 migrations out of order.
-- They retain camelCase columns, while the current Spring naming strategy maps
-- Document and DocumentChunk fields to snake_case. Rename only legacy columns;
-- databases that already use the current schema are left unchanged.

-- documents
SET @schema_name := DATABASE();

SET @legacy_column := 'originalFilename';
SET @target_column := 'original_filename';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN originalFilename TO original_filename', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'contentType';
SET @target_column := 'content_type';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN contentType TO content_type', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'fileSize';
SET @target_column := 'file_size';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN fileSize TO file_size', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'filePath';
SET @target_column := 'file_path';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN filePath TO file_path', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'uploadedAt';
SET @target_column := 'uploaded_at';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN uploadedAt TO uploaded_at', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'processedAt';
SET @target_column := 'processed_at';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN processedAt TO processed_at', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'totalPages';
SET @target_column := 'total_pages';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN totalPages TO total_pages', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'totalChunks';
SET @target_column := 'total_chunks';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN totalChunks TO total_chunks', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'processingError';
SET @target_column := 'processing_error';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'documents' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE documents RENAME COLUMN processingError TO processing_error', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- document_chunks
SET @legacy_column := 'chunkIndex';
SET @target_column := 'chunk_index';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN chunkIndex TO chunk_index', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'startPage';
SET @target_column := 'start_page';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN startPage TO start_page', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'endPage';
SET @target_column := 'end_page';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN endPage TO end_page', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'wordCount';
SET @target_column := 'word_count';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN wordCount TO word_count', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'characterCount';
SET @target_column := 'character_count';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN characterCount TO character_count', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'createdAt';
SET @target_column := 'created_at';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN createdAt TO created_at', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'chapterTitle';
SET @target_column := 'chapter_title';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN chapterTitle TO chapter_title', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'sectionTitle';
SET @target_column := 'section_title';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN sectionTitle TO section_title', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'chapterNumber';
SET @target_column := 'chapter_number';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN chapterNumber TO chapter_number', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @legacy_column := 'sectionNumber';
SET @target_column := 'section_number';
SET @legacy_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @legacy_column);
SET @target_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = @target_column);
SET @sql := IF(@legacy_exists = 1 AND @target_exists = 0, 'ALTER TABLE document_chunks RENAME COLUMN sectionNumber TO section_number', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Legacy chunkType used enum names as strings; the current JPA field is ordinal.
-- Add and backfill the current column before removing the legacy one.
SET @legacy_chunk_type_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = 'chunkType');
SET @target_chunk_type_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'document_chunks' AND column_name = 'chunk_type');
SET @sql := IF(@legacy_chunk_type_exists = 1 AND @target_chunk_type_exists = 0, 'ALTER TABLE document_chunks ADD COLUMN chunk_type TINYINT NULL', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@legacy_chunk_type_exists = 1,
    'UPDATE document_chunks SET chunk_type = CASE CAST(chunkType AS CHAR) WHEN ''CHAPTER'' THEN 0 WHEN ''SECTION'' THEN 1 WHEN ''PAGE_BASED'' THEN 2 WHEN ''SIZE_BASED'' THEN 3 WHEN ''0'' THEN 0 WHEN ''1'' THEN 1 WHEN ''2'' THEN 2 WHEN ''3'' THEN 3 ELSE NULL END WHERE chunk_type IS NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@legacy_chunk_type_exists = 1, 'ALTER TABLE document_chunks DROP COLUMN chunkType', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
