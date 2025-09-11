-- Add refunded_amount_cents column to payments table (idempotent)
-- Check if column exists before adding it
SET @sql = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
     WHERE TABLE_SCHEMA = DATABASE() 
     AND TABLE_NAME = 'payments' 
     AND COLUMN_NAME = 'refunded_amount_cents') = 0,
    'ALTER TABLE payments ADD COLUMN refunded_amount_cents BIGINT NOT NULL DEFAULT 0',
    'SELECT "Column refunded_amount_cents already exists" as message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
