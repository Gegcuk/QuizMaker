-- Billing module initial schema (balances, transactions, reservations, packs, payments, webhook dedupe)

-- balances: per-user token balances
CREATE TABLE IF NOT EXISTS balances (
  user_id BINARY(16) NOT NULL,
  available_tokens BIGINT NOT NULL DEFAULT 0,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_balances_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CHECK (available_tokens >= 0),
  CHECK (reserved_tokens >= 0)
) ENGINE=InnoDB;

-- token_transactions: immutable ledger
CREATE TABLE IF NOT EXISTS token_transactions (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  type ENUM('PURCHASE','RESERVE','COMMIT','RELEASE','REFUND','ADJUSTMENT') NOT NULL,
  source ENUM('QUIZ_GENERATION','AI_CHECK','ADMIN','STRIPE') NOT NULL,
  amount_tokens BIGINT NOT NULL,
  ref_id VARCHAR(255) NULL,
  idempotency_key VARCHAR(255) NULL,
  meta_json JSON NULL,
  balance_after_available BIGINT NULL,
  balance_after_reserved BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_tx_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- Create indexes idempotently (safe re-run)
SET @schema := DATABASE();

-- idx_tx_user_created_at
SET @idx_name := 'idx_tx_user_created_at';
SET @tbl := 'token_transactions';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_tx_user_created_at ON token_transactions(user_id, created_at)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- idx_tx_idempotency_key (unique)
SET @idx_name := 'idx_tx_idempotency_key';
SET @tbl := 'token_transactions';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE UNIQUE INDEX idx_tx_idempotency_key ON token_transactions(idempotency_key)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- reservations: token reservation lifecycle for jobs
CREATE TABLE IF NOT EXISTS reservations (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  state ENUM('ACTIVE','COMMITTED','RELEASED','CANCELLED','EXPIRED') NOT NULL,
  estimated_tokens BIGINT NOT NULL DEFAULT 0,
  committed_tokens BIGINT NOT NULL DEFAULT 0,
  meta_json JSON NULL,
  expires_at TIMESTAMP NOT NULL,
  job_id BINARY(16) NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_res_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CHECK (estimated_tokens >= 0),
  CHECK (committed_tokens >= 0)
) ENGINE=InnoDB;

-- reservations indexes
SET @idx_name := 'idx_res_user_state';
SET @tbl := 'reservations';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_res_user_state ON reservations(user_id, state)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEAlLOCATE PREPARE stmt;

SET @idx_name := 'idx_res_expires_at';
SET @tbl := 'reservations';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_res_expires_at ON reservations(expires_at)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- product_packs: purchasable token packs
CREATE TABLE IF NOT EXISTS product_packs (
  id BINARY(16) NOT NULL,
  name VARCHAR(100) NOT NULL,
  tokens BIGINT NOT NULL,
  price_cents BIGINT NOT NULL,
  currency VARCHAR(10) NOT NULL,
  stripe_price_id VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_packs_stripe_price UNIQUE (stripe_price_id)
) ENGINE=InnoDB;

-- payments: Stripe session/payment records and credited tokens snapshot
CREATE TABLE IF NOT EXISTS payments (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  status ENUM('PENDING','SUCCEEDED','FAILED','REFUNDED') NOT NULL,
  stripe_session_id VARCHAR(255) NOT NULL,
  stripe_payment_intent_id VARCHAR(255) NULL,
  pack_id BINARY(16) NULL,
  amount_cents BIGINT NOT NULL,
  currency VARCHAR(10) NOT NULL,
  credited_tokens BIGINT NOT NULL DEFAULT 0,
  stripe_customer_id VARCHAR(255) NULL,
  session_metadata JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_pay_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_pay_pack FOREIGN KEY (pack_id) REFERENCES product_packs(id),
  CONSTRAINT uq_pay_session UNIQUE (stripe_session_id),
  CONSTRAINT uq_pay_intent UNIQUE (stripe_payment_intent_id),
  CHECK (credited_tokens >= 0)
) ENGINE=InnoDB;

-- payments index
SET @idx_name := 'idx_pay_user_created_at';
SET @tbl := 'payments';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=@schema AND table_name=@tbl AND index_name=@idx_name
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_pay_user_created_at ON payments(user_id, created_at)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- processed_stripe_events: webhook dedupe
CREATE TABLE IF NOT EXISTS processed_stripe_events (
  event_id VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;
