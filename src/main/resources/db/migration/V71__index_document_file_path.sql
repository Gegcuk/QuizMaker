-- Reconciliation resolves bounded candidate batches and performs a final exact
-- reference check before deleting an expired published file.
SET @schema_name := DATABASE();
SET @index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'documents'
      AND index_name = 'idx_documents_file_path'
      AND column_name = 'file_path'
      AND seq_in_index = 1
);
SET @sql := IF(
    @index_exists = 0,
    'CREATE INDEX idx_documents_file_path ON documents (file_path)',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
