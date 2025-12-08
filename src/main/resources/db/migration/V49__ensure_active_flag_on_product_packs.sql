-- Ensure 'active' flag exists on product_packs (safety migration)
-- This is a follow-up to V48 for databases where the column was not created
-- even though V48 is marked as applied.

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

