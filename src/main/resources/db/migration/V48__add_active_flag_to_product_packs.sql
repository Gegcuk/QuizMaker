-- Add 'active' flag to product_packs to track currently sellable packs
-- Make migration idempotent so it can run on existing databases.

SET @schema := (SELECT DATABASE());
SET @tbl := 'product_packs';
SET @col := 'active';

SET @exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = @schema
    AND table_name = @tbl
    AND column_name = @col
);

SET @sql := IF(
  @exists = 0,
  'ALTER TABLE product_packs ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1',
  'DO 0'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
