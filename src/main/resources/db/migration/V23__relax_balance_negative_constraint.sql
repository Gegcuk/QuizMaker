-- Allow negative available token balances when explicitly enabled.
-- Drops legacy CHECK constraints that enforced non-negative available tokens and re-adds reserved constraint with a stable name.
SET @schema := DATABASE();
SET @sql := NULL;

-- Drop combined non-negative constraint if present
SELECT IFNULL(
    (SELECT CONCAT('ALTER TABLE balances DROP CHECK ', tc.CONSTRAINT_NAME)
     FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
     WHERE tc.TABLE_SCHEMA = @schema
       AND tc.TABLE_NAME = 'balances'
       AND tc.CONSTRAINT_TYPE = 'CHECK'
       AND tc.CONSTRAINT_NAME = 'chk_balances_nonneg'
     LIMIT 1),
    'SELECT 1'
) INTO @sql;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Drop legacy available_tokens >= 0 constraint
SELECT IFNULL(
    (SELECT CONCAT('ALTER TABLE balances DROP CHECK ', tc.CONSTRAINT_NAME)
     FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
     JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
       ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
      AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
     WHERE tc.TABLE_SCHEMA = @schema
       AND tc.TABLE_NAME = 'balances'
       AND tc.CONSTRAINT_TYPE = 'CHECK'
       AND cc.CHECK_CLAUSE LIKE '%available_tokens >= 0%'
     LIMIT 1),
    'SELECT 1'
) INTO @sql;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Drop legacy reserved_tokens >= 0 constraint to re-add with explicit name
SELECT IFNULL(
    (SELECT CONCAT('ALTER TABLE balances DROP CHECK ', tc.CONSTRAINT_NAME)
     FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
     JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
       ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
      AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
     WHERE tc.TABLE_SCHEMA = @schema
       AND tc.TABLE_NAME = 'balances'
       AND tc.CONSTRAINT_TYPE = 'CHECK'
       AND cc.CHECK_CLAUSE LIKE '%reserved_tokens >= 0%'
     LIMIT 1),
    'SELECT 1'
) INTO @sql;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Re-create reserved_tokens constraint with a stable name
ALTER TABLE balances
  ADD CONSTRAINT chk_balances_reserved_nonneg
  CHECK (reserved_tokens >= 0);
