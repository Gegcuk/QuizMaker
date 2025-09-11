-- Critical database constraints and indexes for billing module
-- These prevent race conditions and ensure data integrity
-- MySQL-compatible syntax

-- 1. Fix idempotency key constraint (allow NULL values but ensure uniqueness when present)
-- Drop the existing index first (created in V16)
DROP INDEX idx_tx_idempotency_key ON token_transactions;

-- Create proper unique index that allows NULL values
-- MySQL doesn't support partial indexes, so we create a regular unique index
-- NULL values are automatically excluded from unique constraints in MySQL
CREATE UNIQUE INDEX ux_token_tx_idempotency_key 
  ON token_transactions (idempotency_key);

-- 2. Ensure exactly one balance per user (this should already exist as PRIMARY KEY, but let's be explicit)
-- The PRIMARY KEY constraint already ensures this, but let's add a comment for clarity
-- ALTER TABLE balances ADD CONSTRAINT ux_balance_user_id UNIQUE (user_id); -- Already exists as PK

-- 3. Add comprehensive balance constraints
-- MySQL doesn't support partial indexes like PostgreSQL, so we use CHECK constraints
-- The existing CHECK constraints are good, but let's ensure they're comprehensive
ALTER TABLE balances 
  ADD CONSTRAINT chk_balances_nonneg 
  CHECK (available_tokens >= 0 AND reserved_tokens >= 0);

-- 4. Add reservation state invariants
ALTER TABLE reservations 
  ADD CONSTRAINT chk_reservation_amounts 
  CHECK (estimated_tokens >= 0 AND committed_tokens >= 0 AND committed_tokens <= estimated_tokens);

-- 5. Add additional useful indexes for performance
CREATE INDEX idx_tx_user_type_created_at ON token_transactions(user_id, type, created_at);
-- Note: The unique index above already serves as a lookup index for idempotency_key

-- 6. Add constraints to ensure payment integrity
ALTER TABLE payments 
  ADD CONSTRAINT chk_payments_amount_positive 
  CHECK (amount_cents > 0);

-- 7. Add index for faster balance lookups
CREATE INDEX idx_balances_user_version ON balances(user_id, version);

-- 8. Add index for reservation cleanup queries
CREATE INDEX idx_reservations_state_expires ON reservations(state, expires_at);

-- 9. Add constraint to ensure product pack prices are positive
ALTER TABLE product_packs 
  ADD CONSTRAINT chk_packs_price_positive 
  CHECK (price_cents > 0 AND tokens > 0);
